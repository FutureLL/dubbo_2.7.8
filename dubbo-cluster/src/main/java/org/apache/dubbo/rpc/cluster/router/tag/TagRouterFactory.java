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
package org.apache.dubbo.rpc.cluster.router.tag;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.cluster.CacheableRouterFactory;
import org.apache.dubbo.rpc.cluster.Router;

/**
 * Tag router factory
 * 标签路由
 *
 * TagRouterFactory 没有 getRouter() 方法,实现的是父类 CacheableRouterFactory 的 getRouter(),
 * 该方法中又会调用子类的 createRouter() 方法
 * @see TagRouterFactory#createRouter(org.apache.dubbo.common.URL)
 *
 * TagRouter extends AbstractRouter implements ConfigurationListener
 *
 * 设置优先级: this.priority = TAG_ROUTER_DEFAULT_PRIORITY = 100;
 */
@Activate(order = 100)
public class TagRouterFactory extends CacheableRouterFactory {

    public static final String NAME = "tag";

    @Override
    protected Router createRouter(URL url) {
        return new TagRouter(url);
    }
}
