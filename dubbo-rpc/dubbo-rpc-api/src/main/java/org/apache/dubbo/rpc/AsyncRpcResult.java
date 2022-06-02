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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * This class represents an unfinished RPC call, it will hold some context information for this call, for example RpcContext and Invocation,
 * so that when the call finishes and the result returns, it can guarantee all the contexts being recovered as the same as when the call was made
 * before any callback is invoked.
 * <p>
 * TODO if it's reasonable or even right to keep a reference to Invocation?
 * <p>
 * As {@link Result} implements CompletionStage, {@link AsyncRpcResult} allows you to easily build a async filter chain whose status will be
 * driven entirely by the state of the underlying RPC call.
 * <p>
 * AsyncRpcResult does not contain any concrete value (except the underlying value bring by CompletableFuture), consider it as a status transfer node.
 * {@link #getValue()} and {@link #getException()} are all inherited from {@link Result} interface, implementing them are mainly
 * for compatibility consideration. Because many legacy {@link Filter} implementation are most possibly to call getValue directly.
 */
public class AsyncRpcResult extends AbstractResult {
    private static final Logger logger = LoggerFactory.getLogger(AsyncRpcResult.class);

    /**
     * 当相同的线程用于执行另一个RPC调用时，并且回调发生时，原来的RpcContext可能已经被更改。
     * 所以我们应该保留当前RpcContext实例的引用，并在执行回调之前恢复它。
     * 存储当前的RpcContext
     */
    private RpcContext storedContext;
    /**
     * 存储当前的ServerContext
     */
    private RpcContext storedServerContext;
    /**
     * 会话域
     */
    private Invocation invocation;

    public AsyncRpcResult(Invocation invocation) {
        // 设置会话域
        this.invocation = invocation;
        // 获得当前线程内代表消费者端的Context
        this.storedContext = RpcContext.getContext();
        // 获得当前线程内代表服务端的Context
        this.storedServerContext = RpcContext.getServerContext();
    }

    /**
     * 转换成新的AsyncRpcResult
     * @param asyncRpcResult
     */
    public AsyncRpcResult(AsyncRpcResult asyncRpcResult) {
        this.invocation = asyncRpcResult.getInvocation();
        this.storedContext = asyncRpcResult.getStoredContext();
        this.storedServerContext = asyncRpcResult.getStoredServerContext();
    }

    /**
     * Notice the return type of {@link #getValue} is the actual type of the RPC method, not {@link AppResponse}
     *
     * @return
     */
    @Override
    public Object getValue() {
        return getAppResponse().getValue();
    }

    /**
     * CompletableFuture can only be completed once, so try to update the result of one completed CompletableFuture will
     * has no effect. To avoid this problem, we check the complete status of this future before update it's value.
     *
     * But notice that trying to give an uncompleted CompletableFuture a new specified value may face a race condition,
     * because the background thread watching the real result will also change the status of this CompletableFuture.
     * The result is you may lose the value you expected to set.
     *
     * @param value
     */
    @Override
    public void setValue(Object value) {
        try {
            if (this.isDone()) {
                this.get().setValue(value);
            } else {
                AppResponse appResponse = new AppResponse();
                appResponse.setValue(value);
                this.complete(appResponse);
            }
        } catch (Exception e) {
            // This should never happen;
            logger.error("Got exception when trying to change the value of the underlying result from AsyncRpcResult.", e);
        }
    }

    @Override
    public Throwable getException() {
        // 获得抛出的异常信息
        return getAppResponse().getException();
    }

    @Override
    public void setException(Throwable t) {
        try {
            if (this.isDone()) {
                this.get().setException(t);
            } else {
                // 创建一个AppResponse实例
                AppResponse appResponse = new AppResponse();
                // 把异常放入appResponse
                appResponse.setException(t);
                // 标志该future完成，并且把携带异常的appResponse设置为该future的结果
                this.complete(appResponse);
            }
        } catch (Exception e) {
            // This should never happen;
            logger.error("Got exception when trying to change the value of the underlying result from AsyncRpcResult.", e);
        }
    }

    @Override
    public boolean hasException() {
        // 是否有抛出异常
        return getAppResponse().hasException();
    }

    public Result getAppResponse() {
        try {
            // 如果该结果计算完成，则直接调用get方法获得结果
            if (this.isDone()) {
                return this.get();
            }
        } catch (Exception e) {
            // This should never happen;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.", e);
        }
        // 创建AppResponse
        return new AppResponse();
    }

    @Override
    public Object recreate() throws Throwable {
        // 强制类型转化
        RpcInvocation rpcInvocation = (RpcInvocation) invocation;
        FutureAdapter future = new FutureAdapter(this);
        RpcContext.getContext().setFuture(future);
        // 如果返回的是future类型
        if (InvokeMode.FUTURE == rpcInvocation.getInvokeMode()) {
            return future;
        }

        return getAppResponse().recreate();
    }

    @Override
    public Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn) {
        CompletableFuture<Result> future = this.whenComplete((v, t) -> {
            beforeContext.accept(v, t);
            fn.accept(v, t);
            afterContext.accept(v, t);
        });

        AsyncRpcResult nextStage = new AsyncRpcResult(this);
        nextStage.subscribeTo(future);
        return nextStage;
    }

    public void subscribeTo(CompletableFuture<?> future) {
        future.whenComplete((obj, t) -> {
            if (t != null) {
                this.completeExceptionally(t);
            } else {
                this.complete((Result) obj);
            }
        });
    }

    @Override
    public Map<String, String> getAttachments() {
        return getAppResponse().getAttachments();
    }

    @Override
    public void setAttachments(Map<String, String> map) {
        getAppResponse().setAttachments(map);
    }

    @Override
    public void addAttachments(Map<String, String> map) {
        getAppResponse().addAttachments(map);
    }

    @Override
    public String getAttachment(String key) {
        return getAppResponse().getAttachment(key);
    }

    @Override
    public String getAttachment(String key, String defaultValue) {
        return getAppResponse().getAttachment(key, defaultValue);
    }

    @Override
    public void setAttachment(String key, String value) {
        getAppResponse().setAttachment(key, value);
    }

    public RpcContext getStoredContext() {
        return storedContext;
    }

    public RpcContext getStoredServerContext() {
        return storedServerContext;
    }

    public Invocation getInvocation() {
        return invocation;
    }

    /**
     * tmp context to use when the thread switch to Dubbo thread.
     */
    private RpcContext tmpContext;
    private RpcContext tmpServerContext;

    private BiConsumer<Result, Throwable> beforeContext = (appResponse, t) -> {
        tmpContext = RpcContext.getContext();
        tmpServerContext = RpcContext.getServerContext();
        RpcContext.restoreContext(storedContext);
        RpcContext.restoreServerContext(storedServerContext);
    };

    private BiConsumer<Result, Throwable> afterContext = (appResponse, t) -> {
        RpcContext.restoreContext(tmpContext);
        RpcContext.restoreServerContext(tmpServerContext);
    };

    /**
     * Some utility methods used to quickly generate default AsyncRpcResult instance.
     */
    public static AsyncRpcResult newDefaultAsyncResult(AppResponse appResponse, Invocation invocation) {
        AsyncRpcResult asyncRpcResult = new AsyncRpcResult(invocation);
        asyncRpcResult.complete(appResponse);
        return asyncRpcResult;
    }

    public static AsyncRpcResult newDefaultAsyncResult(Invocation invocation) {
        return newDefaultAsyncResult(null, null, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Object value, Invocation invocation) {
        return newDefaultAsyncResult(value, null, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Throwable t, Invocation invocation) {
        return newDefaultAsyncResult(null, t, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Object value, Throwable t, Invocation invocation) {
        AsyncRpcResult asyncRpcResult = new AsyncRpcResult(invocation);
        AppResponse appResponse = new AppResponse();
        if (t != null) {
            appResponse.setException(t);
        } else {
            appResponse.setValue(value);
        }
        asyncRpcResult.complete(appResponse);
        return asyncRpcResult;
    }
}

