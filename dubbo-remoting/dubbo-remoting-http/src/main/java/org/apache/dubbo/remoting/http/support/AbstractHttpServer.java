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
package org.apache.dubbo.remoting.http.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.remoting.http.HttpServer;

import java.net.InetSocketAddress;

/**
 * AbstractHttpServer
 */
public abstract class AbstractHttpServer implements HttpServer {
    /**
     * url
     */
    private final URL url;
    /**
     * http服务器处理器
     */
    private final HttpHandler handler;
    /**
     * 该服务器是否关闭
     */
    private volatile boolean closed;

    public AbstractHttpServer(URL url, HttpHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    @Override
    public HttpHandler getHttpHandler() {
        return handler;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void reset(URL url) {
    }

    @Override
    public boolean isBound() {
        return true;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return url.toInetSocketAddress();
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void close(int timeout) {
        close();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

}
