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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.FutureContext;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {
    /**
     * 信息交换客户端数组
     */
    private final ExchangeClient[] clients;
    /**
     * 客户端数组位置
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();
    /**
     * 版本号
     */
    private final String version;
    /**
     * 销毁锁
     */
    private final ReentrantLock destroyLock = new ReentrantLock();

    /**
     * Invoker对象集合
     */
    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{INTERFACE_KEY, GROUP_KEY, TOKEN_KEY, TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(VERSION_KEY, "0.0.0");
        this.invokers = invokers;
    }

    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        // rpc会话域
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获得方法名
        final String methodName = RpcUtils.getMethodName(invocation);
        // 把path放入到附加值中
        inv.setAttachment(PATH_KEY, getUrl().getPath());
        // 把版本号放入到附加值
        inv.setAttachment(VERSION_KEY, version);

        // 一个DubboInvoker对象可能并发的同时去调用某个服务
        // 那么单独的一次调用都需要一个单独的client去发送请求
        // 所以这里会去选择使用本次调用该使用哪个client
        ExchangeClient currentClient;
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            // 轮询使用clients
            currentClient = clients[index.getAndIncrement() % clients.length];
        }

        try {
            //是否是单向发送 isOneway为true，表示请求不需要拿结果
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            // 拿当前方法的所配置的超时时间，默认为1000,1秒
            int timeout = getUrl().getMethodPositiveParameter(methodName, TIMEOUT_KEY, DEFAULT_TIMEOUT);
            // 如果是单项发送
            if (isOneway) {
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                // 单向发送只负责发送消息，不等待服务端应答，所以没有返回值
                currentClient.send(inv, isSent);
                // 生成一个默认的值的结果，value=null
                return AsyncRpcResult.newDefaultAsyncResult(invocation);
            } else {
                // 异步调用
                AsyncRpcResult asyncRpcResult = new AsyncRpcResult(inv);
                // 异步去请求，得到一个CompletableFuture
                CompletableFuture<Object> responseFuture = currentClient.request(inv, timeout);

                // responseFuture会完成后会调用asyncRpcResult中的方法，这里并不会阻塞，如果要达到阻塞的效果在外层使用asyncRpcResult去控制
                asyncRpcResult.subscribeTo(responseFuture);
                // save for 2.6.x compatibility, for example, TraceFilter in Zipkin uses com.alibaba.xxx.FutureAdapter
                FutureContext.getContext().setCompatibleFuture(responseFuture);
                return asyncRpcResult;
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clients) {
            // 只要有一个客户端连接并且不是只读，则表示存活
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            // 获得销毁锁
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                // 销毁
                super.destroy();
                if (invokers != null) {
                    // 从集合中移除
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {
                        // 关闭每一个客户端
                        client.close(ConfigurationUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                destroyLock.unlock();
            }
        }
    }
}
