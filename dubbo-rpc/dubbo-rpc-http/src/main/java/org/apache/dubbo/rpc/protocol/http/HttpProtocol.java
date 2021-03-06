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
package org.apache.dubbo.rpc.protocol.http;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.remoting.http.tomcat.TomcatHttpBinder;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;

import com.googlecode.jsonrpc4j.HttpException;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

public class HttpProtocol extends AbstractProxyProtocol {
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";

    private final Map<String, JsonRpcServer> skeletonMap = new ConcurrentHashMap<>();

    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(HttpException.class, JsonRpcClientException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return 80;
    }

    private class InternalHandler implements HttpHandler {

        private boolean cors;

        public InternalHandler(boolean cors) {
            this.cors = cors;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String uri = request.getRequestURI();
            /**
             * ?????????????????????,??????????????????????????????
             * @see HttpProtocol#doExport(java.lang.Object, java.lang.Class, org.apache.dubbo.common.URL)
             * skeletonMap.put(path, skeleton);
             */
            JsonRpcServer skeleton = skeletonMap.get(uri);
            // ??????????????????
            if (cors) {
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "POST");
                response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
            }
            if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
                // ?????? OPTIONS ??????
                response.setStatus(200);
            } else if (request.getMethod().equalsIgnoreCase("POST")) {
                // ????????? POST ??????
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    // ?????? JSON ?????????
                    skeleton.handle(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            } else {
                // ?????? METHOD ???????????????,??????: GET ??????,????????????500
                response.setStatus(500);
            }
        }

    }

    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // ???????????????
        String addr = getAddr(url);
        ProtocolServer protocolServer = serverMap.get(addr);
        if (protocolServer == null) {
            /**
             * ?????? url ??????????????? bind() ---> Tomcat ??????
             * @see TomcatHttpBinder#bind(org.apache.dubbo.common.URL, org.apache.dubbo.remoting.http.HttpHandler)
             *
             * InternalHandler: ?????????????????????,????????? Tomcat ???????????? InternalHandler ???????????????
             */
            RemotingServer remotingServer = httpBinder.bind(url, new InternalHandler(url.getParameter("cors", false)));
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        final String path = url.getAbsolutePath();
        final String genericPath = path + "/" + GENERIC_KEY;
        JsonRpcServer skeleton = new JsonRpcServer(impl, type);
        JsonRpcServer genericServer = new JsonRpcServer(impl, GenericService.class);
        /**
         * ???????????? skeletonMap Map ???????????? ?????????
         * @see InternalHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
         * JsonRpcServer skeleton = skeletonMap.get(uri);
         */
        skeletonMap.put(path, skeleton);
        skeletonMap.put(genericPath, genericServer);
        return () -> {
            skeletonMap.remove(path);
            skeletonMap.remove(genericPath);
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        final String generic = url.getParameter(GENERIC_KEY);
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        /********* com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean ???????????????????????????rpc-client??? **************/
        JsonProxyFactoryBean jsonProxyFactoryBean = new JsonProxyFactoryBean();
        JsonRpcProxyFactoryBean jsonRpcProxyFactoryBean = new JsonRpcProxyFactoryBean(jsonProxyFactoryBean);
        jsonRpcProxyFactoryBean.setRemoteInvocationFactory(new RemoteInvocationFactory() {
            @Override
            public RemoteInvocation createRemoteInvocation(MethodInvocation methodInvocation) {
                RemoteInvocation invocation = new JsonRemoteInvocation(methodInvocation);
                if (isGeneric) {
                    invocation.addAttribute(GENERIC_KEY, generic);
                }
                return invocation;
            }
        });
        String key = url.setProtocol("http").toIdentityString();
        if (isGeneric) {
            key = key + "/" + GENERIC_KEY;
        }

        // ??????????????????
        jsonRpcProxyFactoryBean.setServiceUrl(key);
        // ??????????????????
        jsonRpcProxyFactoryBean.setServiceInterface(serviceType);

        jsonProxyFactoryBean.afterPropertiesSet();
        // ????????????????????????,?????????????????? RPC ??????,?????????
        // Protocol#refer ???????????????????????? Invoker
        // AbstractProxyProtocol ?????? proxy ?????? Invoker ??????
        // DubboProtocol ???????????????????????????????????????????????????
        return (T) jsonProxyFactoryBean.getObject();
        /********* com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean ???????????????????????????rpc-client??? **************/
    }

    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }

            if (e instanceof HttpProtocolErrorCode) {
                return ((HttpProtocolErrorCode) e).getErrorCode();
            }
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close jsonrpc server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }


}