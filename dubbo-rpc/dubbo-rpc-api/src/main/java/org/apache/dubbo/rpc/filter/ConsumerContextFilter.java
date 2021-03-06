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

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
/**
 * ConsumerContextFilter set current RpcContext with invoker,invocation, local host, remote host and port
 * for consumer invoker.It does it to make the requires info available to execution thread's RpcContext.
 *
 * @see org.apache.dubbo.rpc.Filter
 * @see RpcContext
 */
@Activate(group = CONSUMER, order = -10000)
public class ConsumerContextFilter extends ListenableFilter {

    public ConsumerContextFilter() {
        super.listener = new ConsumerContextListener();
    }


    /**
     * RpcContext记录了一次调用状态信息，然后先调用后面的调用链，再回来把附加值设置到RpcContext中。然后返回RpcContext，再清空，这样是因为后面的调用链中的附加值对前面的调用链是不可见的。
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得上下文，设置invoker，会话域，本地地址和原创地址
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
                .setLocalAddress(NetUtils.getLocalHost(), 0)
                .setRemoteAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort())
                .setRemoteApplicationName(invoker.getUrl().getParameter(REMOTE_APPLICATION_KEY))
                .setAttachment(REMOTE_APPLICATION_KEY, invoker.getUrl().getParameter(APPLICATION_KEY));
        // 如果该会话域是rpc会话域
        if (invocation instanceof RpcInvocation) {
            // 设置实体域
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        try {
            // 移除服务端的上下文
            RpcContext.removeServerContext();
            // 调用下个调用链
            return invoker.invoke(invocation);
        } finally {
            RpcContext.removeContext();
        }
    }

    static class ConsumerContextListener implements Listener {
        @Override
        public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
            // 设置附加值
            RpcContext.getServerContext().setAttachments(appResponse.getAttachments());
        }

        @Override
        public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

        }
    }
}
