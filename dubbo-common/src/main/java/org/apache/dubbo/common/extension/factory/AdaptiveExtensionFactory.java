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
package org.apache.dubbo.common.extension.factory;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 *
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    /**
     *  扩展对象的集合，默认的可以分为dubbo 的SPI中接口实现类对象或者Spring bean对象
     */
    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        // 支持哪些ExtensionFactory (Spi, SPring)
        //f当用户没有自己实现ExtensionFactory接口，只会有两种对象，分别是 SpiExtensionFactory 和 SpringExtensionFactory
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);

        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();

        //遍历所有支持的扩展名
        for (String name : loader.getSupportedExtensions()) { // spi, spring
            //扩展对象加入到集合中
            list.add(loader.getExtension(name));
        }
        //返回一个不可修改的集合
        factories = Collections.unmodifiableList(list);
    }

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        // 遍历SpringExtensionFactory和SpiExtensionFactory，只要从某个ExtensionFactory中获取到对象实例就可以了
        for (ExtensionFactory factory : factories) {
            //通过扩展接口和扩展名获得扩展对象
            T extension = factory.getExtension(type, name);  // SpringExtensionFactory,, SpiExtensionFactory
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
