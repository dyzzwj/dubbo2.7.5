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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DISABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.ROUTER_KEY;


/**
 * RegistryDirectory
 * 注册中心服务，维护着所有可用的远程Invoker或者本地的Invoker。 它的Invoker集合是从注册中心获取的， 它实现了NotifyListener接口的回调接口notify方法。
 * 比如消费方要调用某远程服务，会向注册中心订阅这个服务的所有服务提供方，订阅时若服务提供方数据有变动时，会回调消费方的NotifyListener服务的notify方法，
 * 回调接口传入所有服务的提供方的url地址然后将urls转化为invokers, 也就是refer应用远程服务
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);
    /**
     * cluster实现类对象
     */
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();
    /**
     * 路由工厂
     */
    private static final RouterFactory ROUTER_FACTORY = ExtensionLoader.getExtensionLoader(RouterFactory.class)
            .getAdaptiveExtension();

    /**
     * 服务key
     */
    private final String serviceKey; // Initialization at construction time, assertion not null
    /**
     * 服务类型
     */
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    /**
     * 消费者URL的配置项 Map
     */
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    /**
     * 原始的目录 URL
     */
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    /**
     * 是否使用多分组
     */
    private final boolean multiGroup;
    /**
     * 创建RegistryDirectory对象时 依赖注入 进来的 注入的是protocol的adaptive类
     */
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    /**
     * 注册中心
     */
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    /**
     *  是否禁止访问
     */
    private volatile boolean forbidden = false;


    /**
     * 覆盖目录的url
     */
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    private volatile URL registeredConsumerUrl;

    /**
     *  配置规则数组
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /**
     * url与服务提供者 Invoker 集合的映射缓存
     */
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /**
     * 所有的服务提供者列表（未进行tag过滤）
     */
    private volatile List<Invoker<T>> invokers;

    /**
     * 服务提供者Invoker 集合缓存
     */
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /**
     * 消费者应用配置监听 一个应用对应一个监听器 多个服务共享同一个消费者应用监听器
     */
    private static final ConsumerConfigurationListener CONSUMER_CONFIGURATION_LISTENER = new ConsumerConfigurationListener();
    /**
     *  服务配置监听器 一个服务对应一个监听器
     */
    private ReferenceConfigurationListener serviceConfigurationListener;


    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = turnRegistryUrlToConsumerUrl(url);
        String group = directoryUrl.getParameter(GROUP_KEY, "");
        this.multiGroup = group != null && (ANY_VALUE.equals(group) || group.contains(","));
    }

    private URL turnRegistryUrlToConsumerUrl(URL url) {
        // save any parameter in registry that will be useful to the new url.
        String isDefault = url.getParameter(DEFAULT_KEY);
        if (StringUtils.isNotEmpty(isDefault)) {
            queryMap.put(REGISTRY_KEY + "." + DEFAULT_KEY, isDefault);
        }
        return URLBuilder.from(url)
                .setPath(url.getServiceInterface())
                .clearParameters()
                .addParameters(queryMap)
                .removeParameter(MONITOR_KEY)
                .build();
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        setConsumerUrl(url);
        CONSUMER_CONFIGURATION_LISTENER.addNotifyListener(this); // 监听consumer应用
        serviceConfigurationListener = new ReferenceConfigurationListener(this, url); // 监听所引入的服务的动态配置
        registry.subscribe(url, this);
    }


    @Override
    public void destroy() {
        // 如果销毁了，则返回
        if (isDestroyed()) {
            return;
        }

        // unregister.
        try {
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
                // 取消订阅
                registry.unregister(getRegisteredConsumerUrl());
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unregister service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
            DynamicConfiguration.getDynamicConfiguration()
                    .removeListener(ApplicationModel.getApplication(), CONSUMER_CONFIGURATION_LISTENER);
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            // 清空所有的invoker
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    @Override
    public synchronized void notify(List<URL> urls) {
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(url -> {
                    if (UrlUtils.isConfigurator(url)) {
                        return CONFIGURATORS_CATEGORY;
                    } else if (UrlUtils.isRoute(url)) {
                        return ROUTERS_CATEGORY;
                    } else if (UrlUtils.isProvider(url)) {
                        return PROVIDERS_CATEGORY;
                    }
                    return "";
                }));

        // 获取动态配置URL，生成configurators
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        //处理配置规则url集合，转换覆盖url映射以便在重新引用时使用，每次发送所有规则，网址将被重新组装和计算。
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);

        // 获取老版本路由URL，生成Router，并添加到路由链中
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        toRouters(routerURLs).ifPresent(this::addRouters);

        // 获取服务提供者URL
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        //重写
        refreshOverrideAndInvoker(providerURLs);
    }

    private void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        overrideDirectoryUrl();

        //刷新Invoker
        refreshInvoker(urls);
    }

    /**
     * 该方法是处理服务提供者 URL 集合。根据 invokerURL 列表转换为 invoker 列表。转换规则如下：
     *
     * 1、如果 url 已经被转换为 invoker ，则不在重新引用，直接从缓存中获取，注意如果 url 中任何一个参数变更也会重新引用。
     * 2、如果传入的 invoker 列表不为空，则表示最新的 invoker 列表。
     * 3、如果传入的 invokerUrl 列表是空，则表示只是下发的 override 规则或 route 规则，需要重新交叉对比，决定是否需要重新引用。
     * @param invokerUrls
     */
    private void refreshInvoker(List<URL> invokerUrls) {   //http://  dubbo://
        Assert.notNull(invokerUrls, "invokerUrls should not be null");

        if (invokerUrls.size() == 1 && invokerUrls.get(0) != null && EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 设置禁止访问
            this.forbidden = true; // Forbid to access
            this.invokers = Collections.emptyList();
            routerChain.setInvokers(this.invokers);
            // 关闭所有的invoker
            destroyAllInvokers(); // Close all invokers
        } else {
            // 关闭禁止访问
            this.forbidden = false; // Allow to access
            // 引用老的 urlInvokerMap
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            // 传入的 invokerUrls 为空，说明是路由规则或配置规则发生改变，此时 invokerUrls 是空的，直接使用 cachedInvokerUrls 。
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                // 否则把所有的invokerUrls加入缓存
                this.cachedInvokerUrls = new HashSet<>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }
            // 如果invokerUrls为空，则直接返回
            if (invokerUrls.isEmpty()) {
                return;
            }
            // 这里会先按Protocol进行过滤，并且调用DubboProtocol.refer方法得到DubboInvoker
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

            /**
             * If the calculation is wrong, it is not processed.
             *
             * 1. The protocol configured by the client is inconsistent with the protocol of the server.
             *    eg: consumer protocol = dubbo, provider only has other protocol services(rest).
             * 2. The registration center is not robust and pushes illegal specification data.
             *
             */
            // 如果为空，则打印错误日志并且返回
            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }

            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            // 得到了所引入的服务Invoker之后，把它们设置到路由链中去，在调用时使用，并且会调用TagRouter的notify方法
            routerChain.setInvokers(newInvokers);
            // 若服务引用多 group ，则按照 method + group 聚合 Invoker 集合
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // 销毁不再使用的 Invoker 集合
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();
        // 循环方法，按照 method + group 聚合 Invoker 集合
        for (Invoker<T> invoker : invokers) {
            String group = invoker.getUrl().getParameter(GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            groupMap.get(group).add(invoker);
        }
        // 如果只有一个组
        if (groupMap.size() == 1) {
            // 返回该组的invoker集合
            mergedInvokers.addAll(groupMap.values().iterator().next());
        } else if (groupMap.size() > 1) { // 如果不止一个组
            for (List<Invoker<T>> groupList : groupMap.values()) {
                // 遍历组
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                staticDirectory.buildRouterChain();
                // 每次从集群中选择一个invoker加入groupInvokers
                mergedInvokers.add(CLUSTER.join(staticDirectory));
            }
        } else {
            mergedInvokers = invokers;
        }
        return mergedInvokers;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private Optional<List<Router>> toRouters(List<URL> urls) {
        // 如果为空，则直接返回空集合
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        // 遍历url集合
        for (URL url : urls) {
            // 如果为empty协议，则直接跳过
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }
            // 获得路由规则
            String routerType = url.getParameter(ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                // 设置协议
                url = url.setProtocol(routerType);
            }
            try {
                // 获得路由
                Router router = ROUTER_FACTORY.getRouter(url);
                if (!routers.contains(router)) {
                    // 加入集合
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     *  该方法是将url转换为调用者，如果url已被引用，则不会重新引用。
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<>();
        // 如果为空，则返回空集合
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<>();
        // 获得引用服务的协议
        String queryProtocols = this.queryMap.get(PROTOCOL_KEY);

        // 遍历当前服务所有的服务提供者URL
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 如果在参考侧配置协议，则仅选择匹配协议
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                // 分割协议
                String[] acceptProtocols = queryProtocols.split(",");

                // 遍历协议当前消费者如果手动配置了Protocol，那么则进行匹配
                for (String acceptProtocol : acceptProtocols) {
                    // 如果匹配，则是接受的协议
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    continue;
                }
            }
            // 如果协议是empty，则跳过
            if (EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }

            // 当前Protocol是否在应用中存在对应的扩展点
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }

            //合并服务提供者和服务消费者的配置  服务消费者的配置覆盖服务提供者的配置   消费端以消费端的配置为准
            URL url = mergeUrl(providerUrl);

            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                continue;
            }
            // 添加到keys
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 如果服务端 URL 发生变化，则重新 refer 引用
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);

            // 如果当前服务提供者URL没有生产过Invoker
            if (invoker == null) { // Not in the cache, refer again
                try {
                    // 判断是否开启
                    boolean enabled = true;
                    // 获得enabled配置
                    if (url.hasParameter(DISABLED_KEY)) {

                        enabled = !url.getParameter(DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(ENABLED_KEY, true);
                    }
                    // 若开启，创建 Invoker 对象
                    if (enabled) {
                        // 调用Protocol的refer方法得到一个Invoker   DubboProtocol.refer()
                        invoker = new InvokerDelegate<>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                // 添加到 newUrlInvokerMap 中
                if (invoker != null) { // Put new invoker in cache
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {
                newUrlInvokerMap.put(key, invoker);
            }
        }
        // 清空 keys
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     *
     *  该方法是合并 URL 参数，优先级为配置规则 > 服务消费者配置 > 服务提供者配置.
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        // 合并消费端参数
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters
        // 合并配置规则
        providerUrl = overrideWithConfigurator(providerUrl);
        // 不检查连接是否成功，总是创建 Invoker
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        // 合并提供者参数，因为 directoryUrl 与 override 合并是在 notify 的最后，这里不能够处理
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters
        // 1.0版本兼容
        if ((providerUrl.getPath() == null || providerUrl.getPath()
                .length() == 0) && DUBBO_PROTOCOL.equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);

        // override url with configurator from configurator from "app-name.configurators"
        providerUrl = overrideWithConfigurators(CONSUMER_CONFIGURATION_LISTENER.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (serviceConfigurationListener != null) {
            providerUrl = overrideWithConfigurators(serviceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    /**
     * Close all invokers
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        // 如果invoker集合不为空
        if (localUrlInvokerMap != null) {
            // 遍历
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    // 销毁invoker
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            // 清空集合
            localUrlInvokerMap.clear();
        }
        invokers = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        // 记录已经删除的invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            // 遍历旧的invoker集合
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    // 加入该invoker
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            // 遍历需要删除的invoker  url集合
            for (String url : deleted) {
                if (url != null) {
                    // 移除该url
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        // 如果禁止访问，则抛出异常
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // Get invokers from cache, only runtime routers will be executed.
            // 路由过滤
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }


        // FIXME Is there any need of failing back to Constants.ANY_VALUE or the first available method invokers when invokers is null?
        /*Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            String methodName = RpcUtils.getMethodName(invocation);
            invokers = localMethodInvokerMap.get(methodName);
            if (invokers == null) {
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }*/
        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    public void setRegisteredConsumerUrl(URL registeredConsumerUrl) {
        this.registeredConsumerUrl = registeredConsumerUrl;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void buildRouterChain(URL url) {
        this.setRouterChain(RouterChain.buildChain(url));
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    public List<Invoker<T>> getInvokers() {
        return invokers;
    }

    private boolean isValidCategory(URL url) {
        String category = url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||
                PROVIDERS_CATEGORY.equals(category) ||
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(COMPATIBLE_CONFIG_KEY));
    }

    // 利用动态配置重写服务目录地址
    private void overrideDirectoryUrl() {
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        //老版本动态配置
        List<Configurator> localConfigurators = this.configurators; // local reference

        doOverrideUrl(localConfigurators);

        //应用动态配置
        List<Configurator> localAppDynamicConfigurators = CONSUMER_CONFIGURATION_LISTENER.getConfigurators(); // local reference
        doOverrideUrl(localAppDynamicConfigurators);

        if (serviceConfigurationListener != null) {
            //服务动态配置
            List<Configurator> localDynamicConfigurators = serviceConfigurationListener.getConfigurators(); // local reference
            doOverrideUrl(localDynamicConfigurators);
        }
    }

    private void doOverrideUrl(List<Configurator> configurators) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        ReferenceConfigurationListener(RegistryDirectory directory, URL url) {
            this.directory = directory;
            this.url = url;
            this.initWith(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshInvoker(Collections.emptyList()));
        }
    }

}
