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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.config.spring.ConfigCenterBean;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.beans.factory.config.ConfigurableSourceBeanMetadataElement;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.NamespaceHandlerSupport;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.w3c.dom.Element;

import static org.apache.dubbo.config.spring.util.DubboBeanUtils.registerCommonBeans;

/**
 * DubboNamespaceHandler
 *
 * @export
 */
public class DubboNamespaceHandler extends NamespaceHandlerSupport implements ConfigurableSourceBeanMetadataElement {

    static {
        Version.checkDuplicate(DubboNamespaceHandler.class);
    }

    /**
     * 对标签中名字为 application 的进行解析,这里 dubbo 使用了统一的 DubboBeanDefinitionParser 进行解析。
     * DubboBeanDefinitionParser 实现了 BeanDefinitionParser 接口,实现 parse 方法对标签进行解析。
     * @see DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)
     * 将解析之后的标签变成 Spring 中的一个 Bean 对象。
     * application 对应一个 ApplicationConfig.
     * 在执行完 init() 方法之后,Spring 会得到所有的下面这个10个 handler.
     *
     * xml 解析初始,bean 对象里面的属性的值,初始值。
     * 设置父节点的值。
     */
    @Override
    public void init() {
        registerBeanDefinitionParser("application", new DubboBeanDefinitionParser(ApplicationConfig.class, true));
        registerBeanDefinitionParser("module", new DubboBeanDefinitionParser(ModuleConfig.class, true));
        registerBeanDefinitionParser("registry", new DubboBeanDefinitionParser(RegistryConfig.class, true));
        registerBeanDefinitionParser("config-center", new DubboBeanDefinitionParser(ConfigCenterBean.class, true));
        registerBeanDefinitionParser("metadata-report", new DubboBeanDefinitionParser(MetadataReportConfig.class, true));
        registerBeanDefinitionParser("monitor", new DubboBeanDefinitionParser(MonitorConfig.class, true));
        registerBeanDefinitionParser("metrics", new DubboBeanDefinitionParser(MetricsConfig.class, true));
        registerBeanDefinitionParser("ssl", new DubboBeanDefinitionParser(SslConfig.class, true));
        registerBeanDefinitionParser("provider", new DubboBeanDefinitionParser(ProviderConfig.class, true));
        registerBeanDefinitionParser("consumer", new DubboBeanDefinitionParser(ConsumerConfig.class, true));
        registerBeanDefinitionParser("protocol", new DubboBeanDefinitionParser(ProtocolConfig.class, true));
        registerBeanDefinitionParser("service", new DubboBeanDefinitionParser(ServiceBean.class, true));
        registerBeanDefinitionParser("reference", new DubboBeanDefinitionParser(ReferenceBean.class, false));
        registerBeanDefinitionParser("annotation", new AnnotationBeanDefinitionParser());
    }

    /**
     * Override {@link NamespaceHandlerSupport#parse(Element, ParserContext)} method
     *
     * @param element       {@link Element}
     * @param parserContext {@link ParserContext}
     * @return
     * @since 2.7.5
     */
    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        BeanDefinitionRegistry registry = parserContext.getRegistry();
        registerAnnotationConfigProcessors(registry);
        /**
         * @since 2.7.8
         * issue : https://github.com/apache/dubbo/issues/6275
         */
        registerCommonBeans(registry);
        BeanDefinition beanDefinition = super.parse(element, parserContext);
        setSource(beanDefinition);
        return beanDefinition;
    }

    /**
     * Register the processors for the Spring Annotation-Driven features
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @see AnnotationConfigUtils
     * @since 2.7.5
     */
    private void registerAnnotationConfigProcessors(BeanDefinitionRegistry registry) {
        AnnotationConfigUtils.registerAnnotationConfigProcessors(registry);
    }
}
