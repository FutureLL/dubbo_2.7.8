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
package org.apache.dubbo.common.compiler.support;

import org.apache.dubbo.common.compiler.Compiler;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;

/**
 * AdaptiveCompiler. (SPI, Singleton, ThreadSafe)
 */
@Adaptive
public class AdaptiveCompiler implements Compiler {

    private static volatile String DEFAULT_COMPILER;

    public static void setDefaultCompiler(String compiler) {
        DEFAULT_COMPILER = compiler;
    }

    @Override
    public Class<?> compile(String code, ClassLoader classLoader) {
        Compiler compiler;
        ExtensionLoader<Compiler> loader = ExtensionLoader.getExtensionLoader(Compiler.class);
        // copy reference
        // 获取用户指定的扩展名
        String name = DEFAULT_COMPILER;
        // 若用户指定了扩展名,则获取用户指定的 compiler,否则获取默认的 compiler
        if (name != null && name.length() > 0) {
            compiler = loader.getExtension(name);
        } else {
            // 默认的 compiler,即 javassistCompiler
            compiler = loader.getDefaultExtension();
        }
        /**
         * 默认调用存在 @SPI 注解中配置的类 @SPI("javassist")
         * 调用 javassistCompiler 类,父类的 compile() 方法
         * @see AbstractCompiler#compile(java.lang.String, java.lang.ClassLoader)
         */
        return compiler.compile(code, classLoader);
    }

}
