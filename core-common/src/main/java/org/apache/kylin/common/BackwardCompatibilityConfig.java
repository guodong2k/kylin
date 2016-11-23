/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.common;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Stack;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

public class BackwardCompatibilityConfig {

    private static final Logger logger = LoggerFactory.getLogger(BackwardCompatibilityConfig.class);

    private static final String KYLIN_BACKWARD_COMPATIBILITY = "kylin-backward-compatibility";

    private final Map<String, String> old2new = Maps.newConcurrentMap();
    private final Map<String, String> old2newPrefix = Maps.newConcurrentMap();

    public BackwardCompatibilityConfig() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        init(loader.getResourceAsStream(KYLIN_BACKWARD_COMPATIBILITY + ".properties"));
        for (int i = 0; i < 10; i++) {
            init(loader.getResourceAsStream(KYLIN_BACKWARD_COMPATIBILITY + (i) + ".properties"));
        }
    }

    // for test
    BackwardCompatibilityConfig(InputStream is) {
        init(is);
    }

    private void init(InputStream is) {
        if (is == null)
            return;

        Properties props = new Properties();
        try {
            props.load(is);
        } catch (IOException e) {
            logger.error("", e);
        } finally {
            IOUtils.closeQuietly(is);
        }

        for (Entry<Object, Object> kv : props.entrySet()) {
            String key = (String) kv.getKey();
            String value = (String) kv.getValue();
            if (value.endsWith("."))
                old2newPrefix.put(key, value);
            else
                old2new.put(key, value);
        }
    }

    public String check(String key) {
        String newKey = old2new.get(key);
        if (newKey != null) {
            logger.warn("Config '{}' is deprecated, use '{}' instead", key, newKey);
            return newKey;
        }

        for (String oldPrefix : old2newPrefix.keySet()) {
            if (key.startsWith(oldPrefix)) {
                String newPrefix = old2newPrefix.get(oldPrefix);
                newKey = newPrefix + key.substring(oldPrefix.length() + 1);
                logger.warn("Config '{}' is deprecated, use '{}' instead", key, newKey);
                return newKey;
            }
        }

        return key;
    }

    public Map<String, String> check(Map<String, String> props) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Entry<String, String> kv : props.entrySet()) {
            result.put(check(kv.getKey()), kv.getValue());
        }
        return result;
    }

    public Properties check(Properties props) {
        Properties result = new Properties();
        for (Entry<Object, Object> kv : props.entrySet()) {
            result.setProperty(check((String) kv.getKey()), (String) kv.getValue());
        }
        return result;
    }

    // ============================================================================

    public static void main(String[] args) throws IOException {
        String kylinRepoDir = args.length > 0 ? args[0] : ".";
        String outputDir = args.length > 1 ? args[1] : ".";
        generateFindAndReplaceScript(kylinRepoDir, outputDir);
    }

    private static void generateFindAndReplaceScript(String kylinRepoPath, String outputPath) throws IOException {
        BackwardCompatibilityConfig bcc = new BackwardCompatibilityConfig();
        File repoDir = new File(kylinRepoPath).getCanonicalFile();
        File outputDir = new File(outputPath).getCanonicalFile();
        PrintWriter out = null;

        // generate sed file
        File sedFile = new File(outputDir, "upgrade-old-config.sed");
        try {
            out = new PrintWriter(sedFile);
            for (Entry<String, String> e : bcc.old2new.entrySet()) {
                out.println("s/" + quote(e.getKey()) + "/" + e.getValue() + "/g");
            }
            for (Entry<String, String> e : bcc.old2newPrefix.entrySet()) {
                out.println("s/" + quote(e.getKey()) + "/" + e.getValue() + "/g");
            }
        } finally {
            IOUtils.closeQuietly(out);
        }

        // generate sh file
        File shFile = new File(outputDir, "upgrade-old-config.sh");
        try {
            out = new PrintWriter(shFile);
            out.println("#!/bin/bash");
            Stack<File> stack = new Stack<>();
            stack.push(repoDir);
            while (!stack.isEmpty()) {
                File dir = stack.pop();
                for (File f : dir.listFiles()) {
                    if (f.getName().startsWith("."))
                        continue;
                    if (f.isDirectory())
                        stack.push(f);
                    else if (isSourceFile(f))
                        out.println("sed -i -f upgrade-old-config.sed " + f.getAbsolutePath());
                }
            }
        } finally {
            IOUtils.closeQuietly(out);
        }
        
        System.out.println("Files generated:");
        System.out.println(shFile);
        System.out.println(sedFile);
    }

    private static String quote(String key) {
        return key.replace(".", "[.]");
    }

    private static boolean isSourceFile(File f) {
        String name = f.getName();
        if (name.startsWith(KYLIN_BACKWARD_COMPATIBILITY))
            return false;
        else
            return name.endsWith(".java") || name.endsWith(".js") || name.endsWith(".sh") || name.endsWith(".properties") || name.endsWith(".xml");
    }
}