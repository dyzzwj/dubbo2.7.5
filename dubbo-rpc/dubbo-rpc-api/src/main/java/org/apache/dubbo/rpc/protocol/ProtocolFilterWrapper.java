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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.rpc.Constants.SERVICE_FILTER_KEY;

/**
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }


    /**
     * 该方法就是创建带 Filter 链的 Invoker 对象。倒序的把每一个过滤器串连起来，形成一个invoker。
     * @param invoker
     * @param key
     * @param group
     * @param <T>
     * @return
     */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {

        //构造链表
        Invoker<T> last = invoker;
        // 获得过滤器的所有符合条件的扩展实现类实例集合
        // 根据url获取filter，根据url中的parameters取key为key的value所对应的filter，但是还会匹配group
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);

        if (!filters.isEmpty()) {
            // 从最后一个过滤器开始循环，创建一个带有过滤器链的invoker对象
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                // 记录last的invoker
                final Invoker<T> next = last;
                // 新建last
                last = new Invoker<T>() {

                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }
                    /**
                     * 关键在这里，调用下一个filter代表的invoker，把每一个过滤器串起来
                     * @param invocation
                     * @return
                     * @throws RpcException
                     */
                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        Result asyncResult;
                        try {
                            // 依次调用各个过滤器，获得最终的返回结果
                            asyncResult = filter.invoke(next, invocation);
                        } catch (Exception e) {
                            // onError callback
                            // 捕获异常，如果该过滤器是ListenableFilter类型的
                            if (filter instanceof ListenableFilter) {
                                // 获得内部类Listener
                                Filter.Listener listener = ((ListenableFilter) filter).listener();
                                if (listener != null) {
                                    //调用onError，回调错误信息
                                    listener.onError(e, invoker, invocation);
                                }
                            }
                            throw e;
                        }
                        return asyncResult;
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }

        return new CallbackRegistrationInvoker<>(last, filters);
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    /**
     * 该方法是在服务暴露上做了过滤器链的增强，也就是加上了过滤器。
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 如果是注册中心，则直接暴露服务
        if (REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        // 服务提供侧暴露服务
        return protocol.export(buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    /**
     * 该方法是在服务引用上做了过滤器链的增强，也就是加上了过滤器。
     * @param type Service class
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 如果是注册中心，则直接引用
        if (REGISTRY_PROTOCOL.equals(url.getProtocol())) {  // dubbo://
            return protocol.refer(type, url);
        }
        // 消费者侧引用服务
        return buildInvokerChain(protocol.refer(type, url), REFERENCE_FILTER_KEY, CommonConstants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    /**
     * Register callback for each filter may be better, just like {@link java.util.concurrent.CompletionStage}, each callback
     * registration generates a new CompletionStage whose status is determined by the original CompletionStage.
     *
     * If bridging status between filters is proved to not has significant performance drop, consider revert to the following commit:
     * https://github.com/apache/dubbo/pull/4127
     */
    static class CallbackRegistrationInvoker<T> implements Invoker<T> {

        private final Invoker<T> filterInvoker;
        private final List<Filter> filters;

        public CallbackRegistrationInvoker(Invoker<T> filterInvoker, List<Filter> filters) {
            this.filterInvoker = filterInvoker;
            this.filters = filters;
        }

        @Override
        public Result invoke(Invocation invocation) throws RpcException {
            // 执行过滤器链
            Result asyncResult = filterInvoker.invoke(invocation);

            // 过滤器都执行完了之后，回调每个ListenableFilter过滤器的onResponse或onError方法
            asyncResult = asyncResult.whenCompleteWithContext((r, t) -> {
                // 循环各个过滤器
                for (int i = filters.size() - 1; i >= 0; i--) {
                    Filter filter = filters.get(i);
                    // onResponse callback
                    // 如果该过滤器是ListenableFilter类型的
                    if (filter instanceof ListenableFilter) {
                        // 强制类型转化
                        Filter.Listener listener = ((ListenableFilter) filter).listener();
                        if (listener != null) {
                            if (t == null) {
                                // 如果内部类listener不为空，则调用回调方法onResponse
                                listener.onResponse(r, filterInvoker, invocation);
                            } else {
                                // 否则，直接调用filter的onResponse，做兼容。
                                listener.onError(t, filterInvoker, invocation);
                            }
                        }
                    } else {
                        filter.onResponse(r, filterInvoker, invocation);
                    }
                }
            });
            return asyncResult;
        }

        @Override
        public Class<T> getInterface() {
            return filterInvoker.getInterface();
        }

        @Override
        public URL getUrl() {
            return filterInvoker.getUrl();
        }

        @Override
        public boolean isAvailable() {
            return filterInvoker.isAvailable();
        }

        @Override
        public void destroy() {
            filterInvoker.destroy();
        }
    }
}
