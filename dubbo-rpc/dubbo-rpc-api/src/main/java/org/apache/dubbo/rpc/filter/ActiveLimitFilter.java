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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.Constants.ACTIVES_KEY;

/**
 * ActiveLimitFilter restrict the concurrent client invocation for a service or service's method from client side.
 * To use active limit filter, configured url with <b>actives</b> and provide valid >0 integer value.
 * <pre>
 *     e.g. <dubbo:reference id="demoService" check="false" interface="org.apache.dubbo.demo.DemoService" "actives"="2"/>
 *      In the above example maximum 2 concurrent invocation is allowed.
 *      If there are more than configured (in this example 2) is trying to invoke remote method, then rest of invocation
 *      will wait for configured timeout(default is 0 second) before invocation gets kill by dubbo.
 * </pre>
 *
 * @see Filter
 */
@Activate(group = CONSUMER, value = ACTIVES_KEY)
public class ActiveLimitFilter extends ListenableFilter {

    private static final String ACTIVELIMIT_FILTER_START_TIME = "activelimit_filter_start_time";

    public ActiveLimitFilter() {
        super.listener = new ActiveLimitListener();
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得url对象
        URL url = invoker.getUrl();
        // 获得方法名称
        String methodName = invocation.getMethodName();
        // 获得并发调用数（单个服务的单个方法），默认为0
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);
        // 通过方法名来获得对应的状态
        final RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        //如果活跃数量大于等于最大的并发调用数量
        if (!RpcStatus.beginCount(url, methodName, max)) {
            // 获得该方法调用的超时次数
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), TIMEOUT_KEY, 0);
            // 获得系统时间
            long start = System.currentTimeMillis();
            long remain = timeout;
            synchronized (rpcStatus) {
                //如果活跃数量大于等于最大的并发调用数量
                while (!RpcStatus.beginCount(url, methodName, max)) {
                    try {
                        //等待
                        rpcStatus.wait(remain);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    // 获得累计时间
                    long elapsed = System.currentTimeMillis() - start;
                    //剩下的超时时间
                    remain = timeout - elapsed;
                    // 过了超时时间 时长，则抛出异常
                    if (remain <= 0) {
                        throw new RpcException(RpcException.LIMIT_EXCEEDED_EXCEPTION,
                                "Waiting concurrent invoke timeout in client-side for service:  " +
                                        invoker.getInterface().getName() + ", method: " + invocation.getMethodName() +
                                        ", elapsed: " + elapsed + ", timeout: " + timeout + ". concurrent invokes: " +
                                        rpcStatus.getActive() + ". max concurrent invoke limit: " + max);
                    }
                }
            }
        }

        invocation.setAttachment(ACTIVELIMIT_FILTER_START_TIME, String.valueOf(System.currentTimeMillis()));

        //调用后面的调用链
        return invoker.invoke(invocation);
    }

    static class ActiveLimitListener implements Listener {
        @Override
        public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
            //回获得方法名
            String methodName = invocation.getMethodName();
            URL url = invoker.getUrl();
            int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);
            // 结束计数，记录时间
            RpcStatus.endCount(url, methodName, getElapsed(invocation), true);
            // 唤醒rpcStatus
            notifyFinish(RpcStatus.getStatus(url, methodName), max);
        }

        @Override
        public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
            String methodName = invocation.getMethodName();
            URL url = invoker.getUrl();
            int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

            if (t instanceof RpcException) {
                RpcException rpcException = (RpcException)t;
                if (rpcException.isLimitExceed()) {
                    return;
                }
            }
            RpcStatus.endCount(url, methodName, getElapsed(invocation), false);
            notifyFinish(RpcStatus.getStatus(url, methodName), max);
        }

        private long getElapsed(Invocation invocation) {
            String beginTime = invocation.getAttachment(ACTIVELIMIT_FILTER_START_TIME);
            return StringUtils.isNotEmpty(beginTime) ? System.currentTimeMillis() - Long.parseLong(beginTime) : 0;
        }

        private void notifyFinish(final RpcStatus rpcStatus, int max) {
            if (max > 0) {
                synchronized (rpcStatus) {
                    rpcStatus.notifyAll();
                }
            }
        }


    }
}
