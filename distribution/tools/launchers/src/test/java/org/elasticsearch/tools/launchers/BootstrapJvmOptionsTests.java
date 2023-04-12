/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.tools.launchers;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.elasticsearch.tools.launchers.BootstrapJvmOptions.PluginInfo;

public class BootstrapJvmOptionsTests extends LaunchersTestCase {

    public void testGenerateOptionsHandlesNoPlugins() {
        final List<String> options = BootstrapJvmOptions.generateOptions(emptyList());
        assertThat(options, is(empty()));
    }

    public void testGenerateOptionsIgnoresNonBootstrapPlugins() {
        Properties props = new Properties();
        props.put("type", "isolated");
        List<PluginInfo> info = singletonList(new PluginInfo(emptyList(), props));

        final List<String> options = BootstrapJvmOptions.generateOptions(info);
        assertThat(options, is(empty()));
    }

    public void testGenerateOptionsHandlesBootstrapPlugins() {
        Properties propsWithoutJavaOpts = new Properties();
        propsWithoutJavaOpts.put("type", "bootstrap");
        PluginInfo info1 = new PluginInfo(singletonList("/path/first.jar"), propsWithoutJavaOpts);

        Properties propsWithEmptyJavaOpts = new Properties();
        propsWithEmptyJavaOpts.put("type", "bootstrap");
        propsWithEmptyJavaOpts.put("java.opts", "");
        PluginInfo info2 = new PluginInfo(singletonList("/path/second.jar"), propsWithEmptyJavaOpts);

        Properties propsWithBlankJavaOpts = new Properties();
        propsWithBlankJavaOpts.put("type", "bootstrap");
        propsWithBlankJavaOpts.put("java.opts", "   \t\n  ");
        PluginInfo info3 = new PluginInfo(singletonList("/path/third.jar"), propsWithBlankJavaOpts);

        Properties propsWithJavaOpts = new Properties();
        propsWithJavaOpts.put("type", "bootstrap");
        propsWithJavaOpts.put("java.opts", "-Dkey=value -DotherKey=otherValue");
        PluginInfo info4 = new PluginInfo(singletonList("/path/fourth.jar"), propsWithJavaOpts);

        final List<String> options = BootstrapJvmOptions.generateOptions(Arrays.asList(info1, info2, info3, info4));
        assertThat(
            options,
            contains(
                "-Dkey=value -DotherKey=otherValue",
                "-Xbootclasspath/a:/path/first.jar:/path/second.jar:/path/third.jar:/path/fourth.jar"
            )
        );
    }
}
