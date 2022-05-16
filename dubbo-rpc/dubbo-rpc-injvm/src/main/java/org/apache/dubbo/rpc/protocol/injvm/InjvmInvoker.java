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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;

import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;

/**
 * InjvmInvoker
 */
class InjvmInvoker<T> extends AbstractInvoker<T> {
    /**
     * 服务key
     */
    private final String key;

    /**
     * 暴露者集合
     */
    private final Map<String, Exporter<?>> exporterMap;

    InjvmInvoker(Class<T> type, URL url, String key, Map<String, Exporter<?>> exporterMap) {
        super(type, url);
        this.key = key;
        this.exporterMap = exporterMap;
    }
    /**
     * 服务是否活跃
     * @return
     */
    @Override
    public boolean isAvailable() {
        InjvmExporter<?> exporter = (InjvmExporter<?>) exporterMap.get(key);
        if (exporter == null) {
            return false;
        } else {
            return super.isAvailable();
        }
    }

    @Override
    public Result doInvoke(Invocation invocation) throws Throwable {
        // 获得暴露者
        Exporter<?> exporter = InjvmProtocol.getExporter(exporterMap, getUrl());
        // 如果为空，则抛出异常
        if (exporter == null) {
            throw new RpcException("Service [" + key + "] not found.");
        }
        // 设置远程地址为127.0.0.1
        RpcContext.getContext().setRemoteAddress(LOCALHOST_VALUE, 0);
        // 调用下一个调用链
        return exporter.getInvoker().invoke(invocation);
    }
}
