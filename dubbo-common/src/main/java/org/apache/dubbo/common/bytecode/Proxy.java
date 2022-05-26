/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.MAX_PROXY_COUNT;

/**
 * Proxy.
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = (proxy, method, args) -> null;
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    private static final Map<ClassLoader, Map<String, Object>> PROXY_CACHE_MAP = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PENDING_GENERATION_MARKER = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(Class<?>... ics) {
        // 获得Proxy的类加载器来进行生成代理类
        return getProxy(ClassUtils.getClassLoader(Proxy.class), ics);
    }


    /**
     * 代码比较多，大致可以分为以下几步：
     *
     * 1、对接口进行校验，检查是否是一个接口，是否不能被类加载器加载。
     * 2、做并发控制，保证只有一个线程可以进行后续的代理生成操作。
     * 3、创建cpp，用作为服务接口生成代理类。首先对接口定义以及包信息进行处理。
     * 4、对接口的方法进行处理，包括返回类型，参数类型等。最后添加方法名、访问控制符、参数列表、方法代码等信息到 ClassGenerator 中。
     * 5、创建接口代理类的信息，比如名称，默认构造方法等。
     * 6、生成接口代理类。
     * 7、创建ccm，ccm 则是用于为 org.apache.dubbo.common.bytecode.Proxy 抽象类生成子类，主要是实现 Proxy 类的抽象方法。
     * 8、设置名称、创建构造方法、添加方法
     * 9、生成 Proxy 实现类。
     * 10、释放资源
     * 11、创建弱引用，写入缓存，唤醒其他线程。
     * @param cl
     * @param ics
     * @return
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        // 最大的代理接口数限制是65535
        if (ics.length > MAX_PROXY_COUNT) {
            throw new IllegalArgumentException("interface limit exceeded");
        }

        StringBuilder sb = new StringBuilder();
        // 遍历代理接口，获取接口的全限定名并以分号分隔连接成字符串
        for (int i = 0; i < ics.length; i++) {
            // 获得类名
            String itf = ics[i].getName();
            // 判断是否为接口
            if (!ics[i].isInterface()) {
                throw new RuntimeException(itf + " is not a interface.");
            }

            Class<?> tmp = null;
            try {
                // 获得与itf对应的Class对象
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }
            // 如果通过类名获得的类型跟ics中的类型不一样，则抛出异常
            if (tmp != ics[i]) {
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");
            }
            // 拼接接口全限定名，分隔符为 ;
            sb.append(itf).append(';');
        }

        // use interface class name list as key.
        // 使用拼接后的接口名作为 key
        String key = sb.toString();

        // get cache by class loader.
        final Map<String, Object> cache;
        // 把该类加载器加到本地缓存
        synchronized (PROXY_CACHE_MAP) {
            // 通过类加载器获得缓存
            cache = PROXY_CACHE_MAP.computeIfAbsent(cl, k -> new HashMap<>());
        }

        Proxy proxy = null;
        synchronized (cache) {
            do {
                // 从缓存中获取 Reference<Proxy> 实例
                Object value = cache.get(key);
                // 如果缓存中存在，则直接返回代理对象
                if (value instanceof Reference<?>) {
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null) {
                        return proxy;
                    }
                }
                // 是等待生成的类型，则等待  并发控制，保证只有一个线程可以进行后续操作
                if (value == PENDING_GENERATION_MARKER) {
                    try {
                        // 其他线程在此处进行等待
                        cache.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    //放置标志位到缓存中，并跳出 while 循环进行后续操作
                    cache.put(key, PENDING_GENERATION_MARKER);
                    break;
                }
            }
            while (true);
        }
        // AtomicLong自增生成代理类类名后缀id，防止冲突
        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        ClassGenerator ccp = null, ccm = null;
        try {
            // 创建 ClassGenerator 对象
            ccp = ClassGenerator.newInstance(cl);

            Set<String> worked = new HashSet<>();
            List<Method> methods = new ArrayList<>();

            for (int i = 0; i < ics.length; i++) {
                // 判断是否为public 检测接口访问级别是否为 protected 或 privete
                if (!Modifier.isPublic(ics[i].getModifiers())) {

                    // 获得该类的包名
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {
                        pkg = npkg;
                    } else {
                        // 非 public 级别的接口必须在同一个包下，否者抛出异常
                        if (!pkg.equals(npkg)) {
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                        }
                    }
                }
                // 把接口加入到ccp的mInterfaces中
                ccp.addInterface(ics[i]);
                // 遍历每个类的方法
                for (Method method : ics[i].getMethods()) {
                    // 获得方法描述 这个方法描述是自定义：
                    // 例如：int do(int arg1) => "do(I)I"
                    // 例如：void do(String arg1,boolean arg2) => "do(Ljava/lang/String;Z)V"
                    String desc = ReflectUtils.getDesc(method);
                    // 如果方法描述字符串已在 worked 中，则忽略。考虑这种情况，
                    // A 接口和 B 接口中包含一个完全相同的方法
                    if (worked.contains(desc)) {
                        continue;
                    }
                    if (ics[i].isInterface() && Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    // 如果集合中不存在，则加入该描述
                    worked.add(desc);

                    int ix = methods.size();
                    // 获得方法返回类型
                    Class<?> rt = method.getReturnType();
                    // 获得方法参数类型
                    Class<?>[] pts = method.getParameterTypes();
                    // 新建一句代码
                    // 例如Object[] args = new Object[参数数量】
                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    // 每一个参数都生成一句代码
                    // 例如args[0] = ($w)$1;
                    // 例如 Object ret = handler.invoke(this, methods[3], args);
                    for (int j = 0; j < pts.length; j++) {
                        // 生成 args[1...N] = ($w)$1...N;
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    }
                    // 生成 InvokerHandler 接口的 invoker 方法调用语句，如下：
                    // Object ret = handler.invoke(this, methods[1...N], args);
                    code.append(" Object ret = handler.invoke(this, methods[").append(ix).append("], args);");
                    // 如果方法不是void类型
                    // 则拼接 return ret;
                    if (!Void.TYPE.equals(rt)) {
                        // 生成返回语句，形如 return (java.lang.String) ret;
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");
                    }

                    methods.add(method);
                    // 添加方法名、访问控制符、参数列表、方法代码等信息到 ClassGenerator 中
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            if (pkg == null) {
                pkg = PACKAGE_NAME;
            }

            // create ProxyInstance class.
            // 构建接口代理类名称：pkg + ".proxy" + id，比如 org.apache.dubbo.proxy0
            String pcn = pkg + ".proxy" + id;
            ccp.setClassName(pcn);
            // 添加静态字段Method[] methods
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            // 生成 private java.lang.reflect.InvocationHandler handler;
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            // 添加实例对象InvokerInvocationHandler hanler，添加参数为InvokerInvocationHandler的构造器
            // 为接口代理类添加带有 InvocationHandler 参数的构造方法，比如：
            // porxy0(java.lang.reflect.InvocationHandler arg0) {
            //     handler=$1;
            // }
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            // 添加默认无参构造器
            ccp.addDefaultConstructor();
            // 使用toClass方法生成对应的字节码
            Class<?> clazz = ccp.toClass();
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            // create Proxy class.
            // 生成的字节码对象为服务接口的代理对象 构建 Proxy 子类名称，比如 Proxy1，Proxy2 等
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);
            // 为 Proxy 的抽象方法 newInstance 生成实现代码，形如：
            // public Object newInstance(java.lang.reflect.InvocationHandler h) {
            //     return new org.apache.dubbo.proxy0($1);
            // }
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");
            Class<?> pc = ccm.toClass();
            // 生成 Proxy 实现类
            proxy = (Proxy) pc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // release ClassGenerator
            // 重置类构造器
            if (ccp != null) {
                ccp.release();
            }
            if (ccm != null) {
                ccm.release();
            }
            synchronized (cache) {
                if (proxy == null) {
                    cache.remove(key);
                } else {
                    cache.put(key, new WeakReference<Proxy>(proxy));
                }
                // 唤醒其他等待线程

                cache.notifyAll();
            }
        }
        return proxy;
    }

    private static String asArgument(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (Boolean.TYPE == cl) {
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            }
            if (Byte.TYPE == cl) {
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            }
            if (Character.TYPE == cl) {
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            }
            if (Double.TYPE == cl) {
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            }
            if (Float.TYPE == cl) {
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            }
            if (Integer.TYPE == cl) {
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            }
            if (Long.TYPE == cl) {
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            }
            if (Short.TYPE == cl) {
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            }
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}
