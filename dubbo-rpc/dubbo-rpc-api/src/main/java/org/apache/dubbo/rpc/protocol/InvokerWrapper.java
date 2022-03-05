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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.filter.ConsumerContextFilter;

/**
 * InvokerWrapper
 */
public class InvokerWrapper<T> implements Invoker<T> {

    private final Invoker<T> invoker;

    private final URL url;

    public InvokerWrapper(Invoker<T> invoker, URL url) {
        this.invoker = invoker;
        this.url = url;
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        /**
         * 先执行 Filter 逻辑
         * @see ProtocolFilterWrapper#buildInvokerChain(org.apache.dubbo.rpc.Invoker, java.lang.String, java.lang.String)
         * 中的 invoke() 方法
         *
         * 按顺序执行下述的 Filter:
         * 0 - ConsumerContextFilter 消费端上下文的 Filter
         * @see ConsumerContextFilter#invoke(org.apache.dubbo.rpc.Invoker, org.apache.dubbo.rpc.Invocation)
         * 1 - FutureFilter
         * @see org.apache.dubbo.rpc.protocol.dubbo.filter.FutureFilter#invoke(org.apache.dubbo.rpc.Invoker, org.apache.dubbo.rpc.Invocation)
         * 2 - MonitorFilter
         * @see org.apache.dubbo.monitor.support.MonitorFilter#invoke(org.apache.dubbo.rpc.Invoker, org.apache.dubbo.rpc.Invocation)
         *
         * 最后会执行 AbstractInvoker 类的 invoke() 方法
         * @see AbstractInvoker#invoke(org.apache.dubbo.rpc.Invocation)
         * 并调用其中的 doInvoke() 方法
         * @see AbstractProxyProtocol#protocolBindingRefer(java.lang.Class, org.apache.dubbo.common.URL)
         * 其中调用 target.invoke() 方法,target 怎么来的呢?
         * 执行如下方法得到的
         * @see org.apache.dubbo.rpc.protocol.http.HttpProtocol#doRefer(java.lang.Class, org.apache.dubbo.common.URL)
         * 最终由 HttpClient 客户端,调用远程地址
         */
        return invoker.invoke(invocation);
    }

    @Override
    public void destroy() {
        invoker.destroy();
    }

}
