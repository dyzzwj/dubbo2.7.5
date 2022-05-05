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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SEPARATOR;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 *
 */
public class ZookeeperRegistry extends FailbackRegistry {

    //日志记录
    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    //默认的zk端口
    private final static String DEFAULT_ROOT = "dubbo";

    // zookeeper根节点
    private final String root;

    // 服务接口集合
    private final Set<String> anyServices = new ConcurrentHashSet<>();

    // 监听器集合
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    // zookeeper客户端实例
    private final ZookeeperClient zkClient;

    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 获得url携带的分组配置，并且作为zookeeper的根节点
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        this.root = group;
        // 创建zookeeper client
        zkClient = zookeeperTransporter.connect(url);

        // 添加状态监听器，当状态为重连的时候调用恢复方法
        zkClient.addStateListener(state -> {
            if (state == StateListener.RECONNECTED) {
                try {
                    //恢复
                    recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doRegister(URL url) {
        try {
            // 创建URL节点，也就是URL层的节点
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnregister(URL url) {
        try {
            //删除节点
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }


    // 进行订阅，先看父类的subscribe方法
    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            // 处理所有Service层发起的订阅，例如监控中心的订阅
            if (ANY_VALUE.equals(url.getServiceInterface())) {
                // 获得根目录
                String root = toRootPath();
                // 获得url对应的监听器集合
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                if (listeners == null) {
                    // 不存在就创建监听器集合
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                    listeners = zkListeners.get(url);
                }
                // 获得节点监听器
                ChildListener zkListener = listeners.get(listener);
                if (zkListener == null) {
                    // 如果该节点监听器为空，则创建
                    listeners.putIfAbsent(listener, (parentPath, currentChilds) -> {
                        for (String child : currentChilds) {
                            child = URL.decode(child);
                            // 遍历现有的节点，如果现有的服务集合中没有该节点，则加入该节点，然后订阅该节点
                            if (!anyServices.contains(child)) {
                                anyServices.add(child);
                                subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child,
                                        Constants.CHECK_KEY, String.valueOf(false)), listener);
                            }
                        }
                    });
                    // 重新获取，为了保证一致性
                    zkListener = listeners.get(listener);
                }
                // 创建service节点，该节点为持久节点
                zkClient.create(root, false);
                // 向zookeeper的service节点发起订阅，获得Service接口全名数组
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (CollectionUtils.isNotEmpty(services)) {
                    // 遍历Service接口全名数组
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        // 发起该service层的订阅
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {
                // 单独订阅某一个服务
                // 处理指定 Service 层的发起订阅，例如服务消费者的订阅
                List<URL> urls = new ArrayList<>();
                // 得到真正要监听的zk上的路径,
                for (String path : toCategoriesPath(url)) {
                    // 根据监听地址去拿listeners，如果没有则生成
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                        listeners = zkListeners.get(url);
                    }

                    // 一个NotifyListener对应一个ChildListener  获得节点监听器
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        // lambda表达式就是监听逻辑， parentPath表示父path,currentChilds表示当前拥有的child, 会调用notify方法进行实际的处理
                        listeners.putIfAbsent(listener, (parentPath, currentChilds) -> ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds)));
                        // 重新获取节点监听器，保证一致性
                        zkListener = listeners.get(listener);
                    }
                    // 创建type节点，该节点为持久节点
                    zkClient.create(path, false);

                    // 向zookeeper的type节点发起订阅添加真正跟zk相关的ChildListener,ChildListener中的逻辑就是监听到zk上数据发生了变化后会触发的逻辑
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                        // 加入到自子节点数据数组
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                // 这里的urls就是从现在所引入的服务的目录下查到的url，比如下面这个三个目录下的路径
//                "/dubbo/org.apache.dubbo.demo.DemoService/providers"
//                "/dubbo/org.apache.dubbo.demo.DemoService/configurators"
//                "/dubbo/org.apache.dubbo.demo.DemoService/routers"
                notify(url, listener, urls);
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        // 获得监听器集合
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            // 获得子节点的监听器
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                // 如果为全部的服务接口，例如监控中心
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    // 获得根目录
                    String root = toRootPath();
                    //移除根目录下所有的监听器
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    //移除了该Service层下面的所有Type节点监听器
                    // 遍历分类数组进行移除监听器
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }
        }
    }

    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<>();
            // 遍历分组类别
            for (String path : toCategoriesPath(url)) {
                //获得子节点
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 获得 providers 中，和 consumer 匹配的 URL 数组
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    /**
     * 获得服务路径，拼接规则：Root + Type。
     * @param url
     * @return
     */
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        // 如果是包括所有服务，则返回根节点
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    /**
     * url携带的服务下的所有Type节点数组
     * @param url
     * @return
     */
    private String[] toCategoriesPath(URL url) {
        String[] categories;
        // 如果url携带的分类配置为*，则创建包括所有分类的数组
        if (ANY_VALUE.equals(url.getParameter(CATEGORY_KEY))) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {
            // 返回url携带的分类配置
            categories = url.getParameter(CATEGORY_KEY, new String[]{DEFAULT_CATEGORY});
        }

        // 根据不同的categories生成zk上对应的路径，比如：
        // url的category参数的值如果是configurators， 那表示要监听/dubbo/org.apache.dubbo.demo.DemoService/configurators路径
        // url的category参数的值如果是providers， 那表示要监听/dubbo/org.apache.dubbo.demo.DemoService/providers路径
        // url的category参数的值如果是consumers， 那表示要监听/dubbo/org.apache.dubbo.demo.DemoService/consumers路径
        // url的category参数的值如果是routers， 那表示要监听/dubbo/org.apache.dubbo.demo.DemoService/routers路径


        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            // 加上服务路径
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    /**
     * 获得分类路径，分类路径拼接规则：Root + Service + Type
     * @param url
     * @return
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    /**
     * 该方法是获得URL路径，拼接规则是Root + Service + Type + URL
     * @param url
     * @return
     */
    private String toUrlPath(URL url) {
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }


    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(providers)) {
            // 遍历服务提供者
            for (String provider : providers) {
                //解码
                provider = URL.decode(provider);
                // provider是一个url
                if (provider.contains(PROTOCOL_SEPARATOR)) {
                    // 把服务转化成url的形式
                    URL url = URL.valueOf(provider);
                    // 判断是否匹配，如果匹配， 则加入到集合中
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    /**
     *
     * @param consumer 表示监听的url，例如provider://192.168.40.17:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=dubbo-demo-annotation-provider&bean.name=ServiceBean:org.apache.dubbo.demo.DemoService&bind.ip=192.168.40.17&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello&pid=345180&release=2.7.0&side=provider&timestamp=1585483924659
     * @param path 表示监听的目录，例如/dubbo/org.apache.dubbo.demo.DemoService/configurators
     * @param providers 表示发生事件时，path下的子节点
     * @return
     */
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        // consumer表示某个服务url，providers表示该服务下的配置，path表示某个服务的配置数据存储路径
        // 过滤providers 返回和服务消费者匹配的服务提供者url
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        // 过滤之后为空，则添加一个empty://协议到urls中，并返回
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf(PATH_SEPARATOR);
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = URLBuilder.from(consumer)
                    .setProtocol(EMPTY_PROTOCOL)
                    .addParameter(CATEGORY_KEY, category)
                    .build();
            urls.add(empty);
        }

        // 所以最后返回的urls，要么只有一个empty://协议，要么就是有几个override://协议
        return urls;
    }

}
