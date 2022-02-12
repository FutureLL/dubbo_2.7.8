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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.compiler.support.AdaptiveCompiler;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory;
import org.apache.dubbo.common.extension.factory.SpiExtensionFactory;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /** 每个接口对应一个 ExtensionLoader */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>(64);

    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>(64);

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /** 加载策略 */
    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        // META-INF/services/, META-INF/dubbo/, META-INF/dubbo/internal/
        // LoadingStrategy 类有三个实现类分别对应上述的路径
        return stream(load(LoadingStrategy.class).spliterator(), false)
                .sorted()
                .toArray(LoadingStrategy[]::new);
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    /**
     * new ExtensionLoader() 对象
     *
     * @param type
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        /**
         * 给 objectFactory 对象添加数据
         * 流程:
         * 1. 传入的 type 是一个接口
         * 2. 不等于 ExtensionFactory.class
         * 3. 因此 objectFactory = ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension();
         *
         * getAdaptiveExtension(): 得到对应的扩展
         * @see ExtensionLoader#getAdaptiveExtension()
         * @see AdaptiveExtensionFactory objectFactory 对象的返回值
         */
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * TODO
     * 得到扩展加载程序
     * ExtensionLoader<Hello> extensionLoader = ExtensionLoader.getExtensionLoader(Hello.class);
     *
     * @see ExtensionLoader#getExtension(java.lang.String)
     *
     * @param type
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 从 Map 中获取 Class 对象
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        // 第一次拿到为 null
        if (loader == null) {
            // 放到 Map 中
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            // 从 Map 中获取
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    public static void destroyAll() {
        EXTENSION_INSTANCES.forEach((_type, instance) -> {
            if (instance instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) instance;
                try {
                    lifecycle.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + lifecycle, e);
                }
            }
        });
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> activateExtensions = new ArrayList<>();
        List<String> names = values == null ? new ArrayList<>(0) : asList(values);
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                if (isMatchGroup(group, activateGroup)
                        && !names.contains(name)
                        && !names.contains(REMOVE_VALUE_PREFIX + name)
                        && isActive(activateValue, url)) {
                    activateExtensions.add(getExtension(name));
                }
            }
            activateExtensions.sort(ActivateComparator.COMPARATOR);
        }
        List<T> loadedExtensions = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                if (DEFAULT_KEY.equals(name)) {
                    if (!loadedExtensions.isEmpty()) {
                        activateExtensions.addAll(0, loadedExtensions);
                        loadedExtensions.clear();
                    }
                } else {
                    loadedExtensions.add(getExtension(name));
                }
            }
        }
        if (!loadedExtensions.isEmpty()) {
            activateExtensions.addAll(loadedExtensions);
        }
        return activateExtensions;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // @Active(value="key1:value1, key2:value2")
            String keyValue = null;
            if (key.contains(":")) {
                String[] arr = key.split(":");
                key = arr[0];
                keyValue = arr[1];
            }

            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ((keyValue != null && keyValue.equals(v)) || (keyValue == null && ConfigUtils.isNotEmpty(v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        // 第一次不存在
        Holder<Object> holder = cachedInstances.get(name);
        // 获取到的值为 null
        if (holder == null) {
            // 存放到 cachedInstances 中
            cachedInstances.putIfAbsent(name, new Holder<>());
            // 再从 cachedInstances 中获取到对应的值
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public List<T> getLoadedExtensionInstances() {
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

//    public T getPrioritizedExtensionInstance() {
//        Set<String> supported = getSupportedExtensions();
//
//        Set<T> instances = new HashSet<>();
//        Set<T> prioritized = new HashSet<>();
//        for (String s : supported) {
//
//        }
//
//    }

    /**
     * TODO
     * 获取到扩展中对应的接口实现类,拿到实例
     * Hello helloImpl = extensionLoader.getExtension("chinaHelloImpl");
     *
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        return getExtension(name, true);
    }

    public T getExtension(String name, boolean wrap) {

        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }

        // @SPI("helloImpl") 在接口中的 @SPI 注解中指定值,这个值表示接口默认的实现类
        if ("true".equals(name)) {
            // 获取默认的实现类
            return getDefaultExtension();
        }

        // 获取或创建 Holder(如果有,从缓存中去取,否则创建)
        final Holder<Object> holder = getOrCreateHolder(name);
        // 得到实现类所对应的实例
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 创建扩展点,也就是说具体的实现类
                    instance = createExtension(name, wrap);
                    // 给 holder 设置一个扩展点
                    holder.set(instance);
                }
            }
        }
        // 不为空返回 instance
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        sort(instances, Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 从缓存自适应实例中获取实例对象
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        /**
                         * 创建自适应扩展,代理类(实现对应的接口的实现类)
                         * 代理类作用: 从 URL 中取值,然后去着对应的实现类
                         *
                         *  Map<String, String> map = new HashMap<>();
                         *  // 指定那个,注入那个实现类
                         *  map.put("hello", "helloImpl");
                         *  URL url = new URL("", "", 1, map);
                         *
                         * @see ExtensionLoader#createAdaptiveExtension()
                         */
                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 创建扩展点,也就是说具体的实现类
     *
     * @param name
     * @param wrap
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        /**
         * 得到我们要找的实现类
         * getExtensionClasses(): 找到所有接口的实现类,将其封装成 Map
         * @see ExtensionLoader#getExtensionClasses()
         */
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            // 生成实例
            // 将 clazz(要找的实现类) 存放到缓存中 EXTENSION_INSTANCES -> Map
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                // key 为该类的 Class 对象,value 为该类的实例
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                // 从 Map 中获取要找的实现类的实例
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 依赖注入
            injectExtension(instance);

            // 包装类处理
            if (wrap) {
                // 包装类排序
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    Collections.reverse(wrapperClassesList);
                }

                /**
                 * AOP 的具体实现
                 * 就是遍历所有的包装类,将每个类通过依赖注入的方式组合注入到包装类中
                 * 也就是装饰器模式的运用,最后生成一个包装了所有类的代理类
                 */
                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    // 如果有多个 wrapper 那么就会,一层一层的去处理
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        if (wrapper == null
                                || (ArrayUtils.contains(wrapper.matches(), name) && !ArrayUtils.contains(wrapper.mismatches(), name))) {
                            // 获取 wrapperClass 类中的构造方法,然后去 new 出来一个实例
                            // injectExtension(): 依赖注入
                            instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                        }
                    }
                }
            }

            // 初始化扩展: 判断是否实现了 Lifecycle 调用其 initialize 方法
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /**
     * 依赖注入
     *
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {

        /**
         * objectFactory 对象何时添加的
         * 第一步:
         * @see ExtensionLoader#getExtensionLoader(java.lang.Class)
         * 第二步:
         * @see ExtensionLoader#ExtensionLoader(java.lang.Class)
         * objectFactory 对象所存储的值
         * @see AdaptiveExtensionFactory objectFactory 对象的返回值
         */
        if (objectFactory == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                // 判断当前遍历的方法是否是setter
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * 若方法上有 @DisableInject 注解,表示该方法不是用于注入的 setter,虽然其方法签名符合 setter 的标准
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 */
                if (method.getAnnotation(DisableInject.class) != null) {
                    continue;
                }

                // 获取到 setter 方法的参数类型,也就是构造方法传入的接口参数
                // 例如: public HelloWrapper2(Hello hello) {this.hello = hello;}
                Class<?> pt = method.getParameterTypes()[0];
                // 若当前 setter 的参数类型为基本数据类型,则不进行处理
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    // 获取 setter 方法的形参名称
                    // 例如: 这里取出的是 hello
                    String property = getSetterProperty(method);
                    /**
                     * 通过 objectFactory 获取指定类型与名称的扩展类实例
                     *
                     * 这里就是 ExtensionFactory 的工作过程:
                     * 1. 先调用 AdaptiveExtensionFactory 的 getExtension() 方法
                     * @see AdaptiveExtensionFactory#getExtension(java.lang.Class, java.lang.String)
                     * 2. 然后在 AdaptiveExtensionFactory 的 getExtension() 方法中会尝试通过 SPI 与 Spring 的 getExtension() 方法获取指定实例
                     *
                     * 注意: SPI 与 Spring 中只要有一个返回就不会继续往下执行
                     *
                     * @see SpiExtensionFactory#getExtension(java.lang.Class, java.lang.String)
                     * @see org.apache.dubbo.config.spring.extension.SpringExtensionFactory#getExtension(java.lang.Class, java.lang.String)
                     *
                     * 取到的结果是 Hello$Adaptive,也就是我们要注入的东西
                     */
                    Object object = objectFactory.getExtension(pt, property);
                    // 若实例不为 null,则调用该 instance 的 setter 方法,方法参数是刚刚获取到的那个 object 实例
                    if (object != null) {
                        /**
                         * 赋值
                         * method: 所对应的实现类的 set() 方法
                         * instance: 所对应的实现类
                         * object: 接口的 Adaptive 代理类
                         */
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName() + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            // 初始化两个 Map: externalConfigurationMap、appExternalConfigurationMap
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    private Map<String, Class<?>> getExtensionClasses() {
        // 从缓存中获取
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 加载扩展类
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 加载扩展类
     *
     * synchronized in getExtensionClasses
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        // 缓存默认扩展名,也就是带有 SPI 注解的接口默认值
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        // META-INF/services/, META-INF/dubbo/, META-INF/dubbo/internal/
        // 根据 strategy 的包名 + 接口名查找所有文件
        // 找到每一个文件按行解析
        // 根据 = 号拆分 key 和 value,key 作为实现类的名称,value 作为 class
        for (LoadingStrategy strategy : strategies) {
            // 加载目录中的文件,封装到 Map 中
            loadDirectory(extensionClasses, strategy.directory(), type.getName(), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
            loadDirectory(extensionClasses, strategy.directory(), type.getName().replace("org.apache", "com.alibaba"), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
        }

        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        // 获取接口所对应 SPI 注解所对应的默认值
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        // 如果 SPI 注解所对应的默认值为空
        if (defaultAnnotation == null) {
            return;
        }

        // 获取注解所对应的值
        String value = defaultAnnotation.value();
        // 所对应值的长度 > 0
        if ((value = value.trim()).length() > 0) {
            // 使用 , 进行分割
            String[] names = NAME_SEPARATOR.split(value);
            // 默认实现类数量 > 1 报错,也就是说一个接口的默认实现类只能有一个
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName() + ": " + Arrays.toString(names));
            }
            // 如果实现类数量为 1
            if (names.length == 1) {
                // 设置默认实现类
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        loadDirectory(extensionClasses, dir, type, false, false);
    }

    /**
     * 加载目录中的文件,封装到 Map 中
     *
     * @param extensionClasses                      存放对象
     * @param dir                                   文件地址
     * @param type                                  接口类型
     * @param extensionLoaderClassLoaderFirst
     * @param overridden
     * @param excludedPackages
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses,
                               String dir,
                               String type,
                               boolean extensionLoaderClassLoaderFirst,
                               boolean overridden,
                               String... excludedPackages) {
        // 拼装文件全路径名
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            ClassLoader classLoader = findClassLoader();

            // try to load from ExtensionLoader's ClassLoader first
            // 首先尝试从 ExtensionLoader 的 ClassLoader 加载
            if (extensionLoaderClassLoaderFirst) {
                // 获取加载 ExtensionLoader.class 这个类的类加载器
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    // 如果 extensionLoaderClassLoaderFirst = true 时,且这两个类加载器不同,就默认使用 extensionLoaderClassLoader
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }

            if (urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    // 从 resources 目录中将指定文件下的文件拿出来
                    // 这里的 urls 可以想像成一行一行的数据
                    // 例如: helloImpl = com.future.dubbo.service.impl.HelloImpl
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            if (urls != null) {
                // 解析并加载配置文件中配置的实现类到 extensionClasses 中去
                while (urls.hasMoreElements()) {
                    // 获取下一行数据
                    java.net.URL resourceURL = urls.nextElement();
                    // 加载资源文件
                    loadResource(extensionClasses, classLoader, resourceURL, overridden, excludedPackages);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * loadResource 主要用于读取文件操作,并将读取到的信息交给 loadClass 加载
     *
     * @param extensionClasses      装载容器
     * @param classLoader
     * @param resourceURL           配置文件资源URL
     * @param overridden
     * @param excludedPackages
     */
    private void loadResource(Map<String, Class<?>> extensionClasses,
                              ClassLoader classLoader,
                              java.net.URL resourceURL,
                              boolean overridden,
                              String... excludedPackages) {
        try {
            // 读取文件
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                // 行为单位进行读取
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0 && !isExcluded(line, excludedPackages)) {
                                // 加载类
                                // Class.forName(): 获取 Class 对象
                                // line: com.future.dubbo.service.impl.HelloImpl
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name, overridden);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 加载扩展点的实现的 class,这个类的主要作用是对解析到的 class 进行分类缓存
     *
     * @param extensionClasses          装载容器
     * @param resourceURL               配置文件资源URL
     * @param clazz                     扩展点实现类的class
     * @param name                      扩展点实现类的名称,别名
     * @throws NoSuchMethodException    加载异常
     */
    private void loadClass(Map<String, Class<?>> extensionClasses,
                           java.net.URL resourceURL,
                           Class<?> clazz,
                           String name,
                           boolean overridden) throws NoSuchMethodException {

        // 判断 clazz 实现类是否实现了 type 类型的接口
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " + type + ", class line: " + clazz.getName() + "), class " + clazz.getName() + " is not subtype of interface.");
        }

        /**
         * 调用方法/类的时候找到对应的实现类
         * 也就是 Map 中 key 为 hello 所对应的 value 值
         * @Adaptive("hello")
         */
        // 判断实现类是否存在 @Adaptive 注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            // 缓存带有 @Adaptive 的类
            cacheAdaptiveClass(clazz, overridden);
        }
        // 是否为 Wrapper 包装类
        else if (isWrapperClass(clazz)) {
            // 缓存包装类
            cacheWrapperClass(clazz);
        }
        // 既没有 @Adaptive 注解,也不是包装类
        else {
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            // 获取 name,例如: red,bigRed 这是两个名字,将其变成数组
            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 缓存 @Activate 注解的类
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // 缓存实现类以及其所对应的名称,只会将第一个名字进行存储,例如: red,bigRed 两个名字只会存储 red
                    cacheName(clazz, n);
                    // 保存扩展类,存放到 extensionClasses 中,如果有多个名字会在 extensionClasses 的 Map 中存放多条记录
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            // key 为实现类,value 为名称
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        // 从传入的 Map 中获取
        Class<?> c = extensionClasses.get(name);
        // 如果为空
        if (c == null || overridden) {
            // 添加到 Map 中
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: " + cachedAdaptiveClass.getName() + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            // 判断实现类是否有接口类型的构造方法
            /**
             * // Hello 接口
             * // HelloWrapper 实现类
             *
             * private Hello hello;
             * public HelloWrapper(Hello hello) {
             *     this.hello = hello;
             * }
             */
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // injectExtension(): 依赖注入,注入自适应的扩展实现类
            // getAdaptiveExtensionClass(): 获得自适应扩展类
            // newInstance(): 创建新实例
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        // 得到扩展类
        // getExtensionClasses(): 找到所有接口的实现类,将其封装成 Map
        getExtensionClasses();
        /**
         * cachedAdaptiveClass 存放带有 @Adaptive 注解的类,如果 cachedAdaptiveClass 对象不为空,那么直接返回该类
         * 也就是带有 @Adaptive 注解的类为该类接口的代理类
         *
         * cachedAdaptiveClass 如果为空,则表示没有手动取定义代理类
         */
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 默认创建一个代理类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    private Class<?> createAdaptiveExtensionClass() {
        // 其内容就是动态生成的 Adaptive 扩展类
        // 根据 SPI 接口和缓存中扩展点默认的名称来构建一个自适应扩展点的 Class 的代码生产器,生产 String 类型的 java 文件
        // 创建默认代理类,默认为 cachedDefaultName 所对应的实现类
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        ClassLoader classLoader = findClassLoader();
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        /**
         * 调用 compiler 的 adaptive 实例的 compile()
         * @see AdaptiveCompiler#compile(java.lang.String, java.lang.ClassLoader)
         */
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
