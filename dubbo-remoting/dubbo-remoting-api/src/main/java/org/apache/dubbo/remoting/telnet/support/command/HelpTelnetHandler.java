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
package org.apache.dubbo.remoting.telnet.support.command;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.telnet.TelnetHandler;
import org.apache.dubbo.remoting.telnet.support.Help;
import org.apache.dubbo.remoting.telnet.support.TelnetUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * HelpTelnetHandler
 */
@Activate
@Help(parameter = "[command]", summary = "Show help.", detail = "Show help.")
public class HelpTelnetHandler implements TelnetHandler {
    /**
     * 扩展加载器
     */
    private final ExtensionLoader<TelnetHandler> extensionLoader = ExtensionLoader.getExtensionLoader(TelnetHandler.class);

    @Override
    public String telnet(Channel channel, String message) {
        // 如果需要查看某一个命令的帮助
        if (message.length() > 0) {
            if (!extensionLoader.hasExtension(message)) {
                return "No such command " + message;
            }
            // 获得对应的扩展实现类
            TelnetHandler handler = extensionLoader.getExtension(message);
            Help help = handler.getClass().getAnnotation(Help.class);
            StringBuilder buf = new StringBuilder();
            // 生成命令和帮助信息
            buf.append("Command:\r\n    ");
            buf.append(message + " " + help.parameter().replace("\r\n", " ").replace("\n", " "));
            buf.append("\r\nSummary:\r\n    ");
            buf.append(help.summary().replace("\r\n", " ").replace("\n", " "));
            buf.append("\r\nDetail:\r\n    ");
            buf.append(help.detail().replace("\r\n", "    \r\n").replace("\n", "    \n"));
            return buf.toString();
            // 如果查看所有命令的帮助
        } else {
            List<List<String>> table = new ArrayList<List<String>>();
            // 获得所有命令的提示信息
            List<TelnetHandler> handlers = extensionLoader.getActivateExtension(channel.getUrl(), "telnet");
            if (CollectionUtils.isNotEmpty(handlers)) {
                for (TelnetHandler handler : handlers) {
                    Help help = handler.getClass().getAnnotation(Help.class);
                    List<String> row = new ArrayList<String>();
                    String parameter = " " + extensionLoader.getExtensionName(handler) + " " + (help != null ? help.parameter().replace("\r\n", " ").replace("\n", " ") : "");
                    row.add(parameter.length() > 55 ? parameter.substring(0, 55) + "..." : parameter);
                    String summary = help != null ? help.summary().replace("\r\n", " ").replace("\n", " ") : "";
                    row.add(summary.length() > 55 ? summary.substring(0, 55) + "..." : summary);
                    table.add(row);
                }
            }
            return "Please input \"help [command]\" show detail.\r\n" + TelnetUtils.toList(table);
        }
    }

}
