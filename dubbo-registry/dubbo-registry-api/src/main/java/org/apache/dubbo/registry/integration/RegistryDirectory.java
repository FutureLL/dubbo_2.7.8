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
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.AddressListener;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.support.FailbackRegistry;
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
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
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
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.registry.integration.RegistryProtocol.DEFAULT_REGISTER_CONSUMER_KEYS;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.ROUTER_KEY;


/**
 * RegistryDirectory
 * 有监听器
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final RouterFactory ROUTER_FACTORY = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    private final String serviceKey; // Initialization at construction time, assertion not null
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    private final boolean multiGroup;
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    private volatile boolean forbidden = false;
    private boolean shouldRegister;
    private boolean shouldSimplified;

    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    private volatile URL registeredConsumerUrl;

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /** 多个提供者 */
    private volatile List<Invoker<T>> invokers;

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    private static final ConsumerConfigurationListener CONSUMER_CONFIGURATION_LISTENER = new ConsumerConfigurationListener();
    private ReferenceConfigurationListener serviceConfigurationListener;


    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }

        // 判断是否需要注册
        shouldRegister = !ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true);
        shouldSimplified = url.getParameter(SIMPLIFIED_KEY, false);
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

    public Registry getRegistry() {
        return registry;
    }

    public boolean isShouldRegister() {
        return shouldRegister;
    }

    public void subscribe(URL url) {
        setConsumerUrl(url);
        // 监听 Consumer 应用
        CONSUMER_CONFIGURATION_LISTENER.addNotifyListener(this);
        // 监听所引入的服务的动态配置
        serviceConfigurationListener = new ReferenceConfigurationListener(this, url);
        /**
         * 这里又是 SPI,会先调用 FailbackRegistry
         * @see FailbackRegistry#subscribe(org.apache.dubbo.common.URL, org.apache.dubbo.registry.NotifyListener)
         */
        registry.subscribe(url, this);
    }

    public void unSubscribe(URL url) {
        setConsumerUrl(null);
        CONSUMER_CONFIGURATION_LISTENER.removeNotifyListener(this);
        serviceConfigurationListener.stop();
        registry.unsubscribe(url, this);
    }


    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // unregister.
        try {
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
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
            ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                    .removeListener(ApplicationModel.getApplication(), CONSUMER_CONFIGURATION_LISTENER);
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * 当 Consumer 配置发生改变时,有事件进来,会调用到这个方法
     *
     * @param urls The list of registered information , is always not empty. The meaning is the same as the return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
     */
    @Override
    public synchronized void notify(List<URL> urls) {
        // 过滤一些不需要监听的文件路径
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(this::judgeCategory));

        // 获取 /config/configurators 目录下的规则
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);

        // 获取路由规则
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        // addRouters(): 将路由规则添加到路由链中
        toRouters(routerURLs).ifPresent(this::addRouters);

        // 获取到 com.future.dubbo.service.Hello/providers/ 路径,也就是服务提供者
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        // Dubbo 3.x added for extend URL address
        // 监听器扩展加载程序: 获取 AddressListener
        ExtensionLoader<AddressListener> addressListenerExtensionLoader = ExtensionLoader.getExtensionLoader(AddressListener.class);
        List<AddressListener> supportedListeners = addressListenerExtensionLoader.getActivateExtension(getUrl(), (String[]) null);
        if (supportedListeners != null && !supportedListeners.isEmpty()) {
            for (AddressListener addressListener : supportedListeners) {
                // 处理通知
                providerURLs = addressListener.notify(providerURLs, getConsumerUrl(),this);
            }
        }
        // 刷新 invoker 列表
        refreshOverrideAndInvoker(providerURLs);
    }

    private String judgeCategory(URL url) {
        if (UrlUtils.isConfigurator(url)) {
            return CONFIGURATORS_CATEGORY;
        } else if (UrlUtils.isRoute(url)) {
            return ROUTERS_CATEGORY;
        } else if (UrlUtils.isProvider(url)) {
            return PROVIDERS_CATEGORY;
        }
        return "";
    }

    private void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        // 重写服务目录
        overrideDirectoryUrl();
        // 刷新 Invoker
        refreshInvoker(urls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * <ol>
     * <li> If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache,
     * and notice that any parameter changes in the URL will be re-referenced.</li>
     * <li>If the incoming invoker list is not empty, it means that it is the latest invoker list.</li>
     * <li>If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route
     * rule, which needs to be re-contrasted to decide whether to re-reference.</li>
     * </ol>
     *
     * @param invokerUrls this parameter can't be null.
     *
     * refreshInvoker 方法首先会根据入参 invokerUrls 的数量和协议头判断是否禁用所有的服务,
     * 如果禁用,则将 forbidden 设为 true,并销毁所有的 Invoker。
     * 若不禁用,则将 url 转成 Invoker,得到 <url, Invoker> 的映射关系。
     * 然后进一步进行转换,得到 <methodName, Invoker 列表> 映射关系。
     * 之后进行多组 Invoker 合并操作,并将合并结果赋值给 methodInvokerMap。
     * methodInvokerMap 变量在 doList 方法中会被用到,doList 会对该变量进行读操作,在这里是写操作。
     * 当新的 Invoker 列表生成后,还要一个重要的工作要做,就是销毁无用的 Invoker,避免服务消费者调用已下线的服务的服务。
     */
    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null");

        // invokerUrls 仅有一个元素,且 url 协议头为 empty://,此时表示禁用所有服务
        if (invokerUrls.size() == 1
                && invokerUrls.get(0) != null
                && EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // Forbid to access
            // 禁止访问
            this.forbidden = true;
            this.invokers = Collections.emptyList();
            routerChain.setInvokers(this.invokers);
            // Close all invokers
            destroyAllInvokers();
        } else {
            // Allow to access
            // 允许访问
            this.forbidden = false;
            // local reference
            // 本地旧 Invoker
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap;
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 添加缓存 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                this.cachedInvokerUrls = new HashSet<>();
                // Cached invoker urls, convenient for comparison
                // 缓存 invokerUrls
                this.cachedInvokerUrls.addAll(invokerUrls);
            }
            if (invokerUrls.isEmpty()) {
                return;
            }
            // Translate url list to Invoker map
            // ************************
            // ** 将 url 转成 Invoker **
            // ************************
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);

            /**
             * If the calculation is wrong, it is not processed.
             *
             * 1. The protocol configured by the client is inconsistent with the protocol of the server.
             *    eg: consumer protocol = dubbo, provider only has other protocol services(rest).
             * 2. The registration center is not robust and pushes illegal specification data.
             *
             */
            // 转换出错,直接打印异常,并返回
            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }

            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            // pre-route 及构建 cache,请注意,路由缓存应该建立在原始 Invoker 列表上。
            // toMergeMethodInvokerMap(): 将包装一些具有不同组的调用者,那些包装的调用者不应该被路由。
            routerChain.setInvokers(newInvokers);
            // 合并多个组的 Invoker,代表的是所有的服务提供者
            // 这里的 invokers 为 InvokerDelegate 对象
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // Close the unused Invoker
                // 销毁无用 Invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap);
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();
        for (Invoker<T> invoker : invokers) {
            String group = invoker.getUrl().getParameter(GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            groupMap.get(group).add(invoker);
        }

        if (groupMap.size() == 1) {
            mergedInvokers.addAll(groupMap.values().iterator().next());
        } else if (groupMap.size() > 1) {
            for (List<Invoker<T>> groupList : groupMap.values()) {
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                staticDirectory.buildRouterChain();
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
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        for (URL url : urls) {
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }
            String routerType = url.getParameter(ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                url = url.setProtocol(routerType);
            }
            try {
                Router router = ROUTER_FACTORY.getRouter(url);
                if (!routers.contains(router)) {
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * 1.对服务提供者 url 进行检测,若服务消费端的配置不支持服务端的协议,或服务端 url 协议头为 empty 时,toInvokers 均会忽略服务提供方 url.
     * 2.必要的检测做完后,紧接着是合并 url.
     * 3.然后访问缓存,尝试获取与 url 对应的 invoker.
     *  (1)缓存命中,直接将 Invoker 存入 newUrlInvokerMap 中即可.
     *  (2)未命中,根据 Protocol 新建 Invoker,其中 Invoker 的监听器、过滤器都在此时根据 Dubbo SPI 被加载,在 Dubbo 调用过程中将会一一执行.
     *
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<>();
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<>();
        // 获取服务消费端配置的协议
        String queryProtocols = this.queryMap.get(PROTOCOL_KEY);
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 如果在 reference 端配置了协议,则只选择匹配的协议
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");
                // 检测服务提供者协议是否被服务消费者所支持
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    // 若服务提供者协议头不被消费者所支持,则忽略当前 providerUrl
                    continue;
                }
            }
            // 忽略 empty 协议
            if (EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            // 通过 SPI 检测服务端协议是否被消费端支持,不支持则抛出异常
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并 url
            URL url = mergeUrl(providerUrl);

            // The parameter urls are sorted
            String key = url.toFullString();
            // Repeated url
            if (keys.contains(key)) {
                // 忽略重复 url
                continue;
            }
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // local reference
            // 缓存 key 是不与 consumer 端参数合并的 url,不管 consumer 如何合并参数,如果 server url 发生变化,再参考
            // 将本地 Invoker 缓存赋值给 localUrlInvokerMap
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap;
            // 获取与 url 对应的 Invoker
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            // Not in the cache, refer again
            // 缓存未命中
            if (invoker == null) {
                try {
                    boolean enabled = true;
                    if (url.hasParameter(DISABLED_KEY)) {
                        // 获取 disable 配置,取反,然后赋值给 enable 变量
                        enabled = !url.getParameter(DISABLED_KEY, false);
                    } else {
                        // 获取 enable 配置,并赋值给 enable 变量
                        enabled = url.getParameter(ENABLED_KEY, true);
                    }
                    if (enabled) {
                        /**
                         * 调用 refer 获取 Invoker --- 使用的是 http 协议
                         * @see org.apache.dubbo.rpc.protocol.http.HttpProtocol
                         * 执行父类的 refer(),最终会走到 HttpProtocol 的 doRefer()
                         * @see org.apache.dubbo.rpc.protocol.http.HttpProtocol#doRefer(java.lang.Class, org.apache.dubbo.common.URL)
                         *
                         * InvokerDelegate 也实现了 Invoker 接口,因此 InvokerDelegate 也有一个自己的 invoke() 方法
                         * @see InvokerWrapper#invoke(org.apache.dubbo.rpc.Invocation)
                         */
                        invoker = new InvokerDelegate<>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                // Put new invoker in cache
                if (invoker != null) {
                    // 缓存 Invoker 实例
                    newUrlInvokerMap.put(key, invoker);
                }
            }
            // 缓存命中
            else {
                // 将 invoker 存储到 newUrlInvokerMap 中
                newUrlInvokerMap.put(key, invoker);
            }
        }
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        providerUrl = overrideWithConfigurator(providerUrl);

        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

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
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
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
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            for (String url : deleted) {
                if (url != null) {
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
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
            // 通过路由链,从缓存中获取调用程序
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }

        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public List<Invoker<T>> getAllInvokers() {
        return invokers;
    }

    @Override
    public URL getConsumerUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    public void setRegisteredConsumerUrl(URL url) {
        if (!shouldSimplified) {
            this.registeredConsumerUrl = url.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY,
                    String.valueOf(false));
        } else {
            this.registeredConsumerUrl = URL.valueOf(url, DEFAULT_REGISTER_CONSUMER_KEYS, null).addParameters(
                    CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY, String.valueOf(false));
        }
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
        // 创建路由链,并设置到 routerChain 对象上
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
        // 新版本已经存在 compatible_config,因此没必要监听另外一个目录: /dubbo/com.future.dubbo.service.Hello/configurators
        return StringUtils.isEmpty(url.getParameter(COMPATIBLE_CONFIG_KEY));
    }

    private void overrideDirectoryUrl() {
        // merge override parameters
        // 合并 override 参数
        this.overrideDirectoryUrl = directoryUrl;
        // local reference
        // 本地 reference
        List<Configurator> localConfigurators = this.configurators;
        // 执行重写
        doOverrideUrl(localConfigurators);

        // 本地应用动态配置
        List<Configurator> localAppDynamicConfigurators = CONSUMER_CONFIGURATION_LISTENER.getConfigurators(); // local reference
        // 执行重写
        doOverrideUrl(localAppDynamicConfigurators);

        if (serviceConfigurationListener != null) {
            // 局部动态配置
            List<Configurator> localDynamicConfigurators = serviceConfigurationListener.getConfigurators(); // local reference
            // 执行重写
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
            // 初始化
            this.initWith(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        void stop() {
            this.stopListen(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshOverrideAndInvoker(Collections.emptyList());
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

        void removeNotifyListener(RegistryDirectory listener) {
            this.listeners.remove(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshOverrideAndInvoker(Collections.emptyList()));
        }
    }

}
