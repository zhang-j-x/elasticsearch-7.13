/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.tools.launchers;

import org.elasticsearch.tools.java_version_checker.JavaVersion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

final class SystemJvmOptions {

    static List<String> systemJvmOptions() {
        return Collections.unmodifiableList(
            Arrays.asList(
                /*
                 * Cache ttl in seconds for positive DNS lookups noting that this overrides the JDK security property
                 * networkaddress.cache.ttl; can be set to -1 to cache forever.
                 */
                "-Des.networkaddress.cache.ttl=60",
                /*
                 * Cache ttl in seconds for negative DNS lookups noting that this overrides the JDK security property
                 * networkaddress.cache.negative ttl; set to -1 to cache forever.
                 */
                "-Des.networkaddress.cache.negative.ttl=10",
                // pre-touch JVM emory pages during initialization
                "-XX:+AlwaysPreTouch",
                // explicitly set the stack size
                "-Xss1m",
                // set to headless, just in case,
                "-Djava.awt.headless=true",
                // ensure UTF-8 encoding by default (e.g., filenames)
                "-Dfile.encoding=UTF-8",
                // use our provided JNA always versus the system one
                "-Djna.nosys=true",
                /*
                 * Turn off a JDK optimization that throws away stack traces for common exceptions because stack traces are important for
                 * debugging.
                 */
                "-XX:-OmitStackTraceInFastThrow",
                // enable helpful NullPointerExceptions (https://openjdk.java.net/jeps/358), if they are supported
                maybeShowCodeDetailsInExceptionMessages(),
                // flags to configure Netty
                "-Dio.netty.noUnsafe=true",
                "-Dio.netty.noKeySetOptimization=true",
                "-Dio.netty.recycler.maxCapacityPerThread=0",
                "-Dio.netty.allocator.numDirectArenas=0",
                // log4j 2
                "-Dlog4j.shutdownHookEnabled=false",
                "-Dlog4j2.disable.jmx=true",

                javaLocaleProviders(),
                maybeAddOpensJavaIoToAllUnnamed()
            )
        ).stream().filter(e -> e.isEmpty() == false).collect(Collectors.toList());
    }

    private static String maybeShowCodeDetailsInExceptionMessages() {
        if (JavaVersion.majorVersion(JavaVersion.CURRENT) >= 14) {
            return "-XX:+ShowCodeDetailsInExceptionMessages";
        } else {
            return "";
        }
    }

    private static String javaLocaleProviders() {
        /**
         *  SPI setting is used to allow loading custom CalendarDataProvider
         *  in jdk8 it has to be loaded from jre/lib/ext,
         *  in jdk9+ it is already within ES project and on a classpath
         *
         *  Due to internationalization enhancements in JDK 9 Elasticsearch need to set the provider to COMPAT otherwise time/date
         *  parsing will break in an incompatible way for some date patterns and locales.
         *  //TODO COMPAT will be deprecated in jdk14 https://bugs.openjdk.java.net/browse/JDK-8232906
         * See also: documentation in <code>server/org.elasticsearch.common.time.IsoCalendarDataProvider</code>
         */
        if (JavaVersion.majorVersion(JavaVersion.CURRENT) == 8) {
            return "-Djava.locale.providers=SPI,JRE";
        } else {
            return "-Djava.locale.providers=SPI,COMPAT";
        }
    }

    private static String maybeAddOpensJavaIoToAllUnnamed() {
        /*
         * Temporarily suppress illegal reflective access in searchable snapshots shared cache preallocation; this is temporary while we
         * explore alternatives. See org.elasticsearch.xpack.searchablesnapshots.preallocate.Preallocate.
         *
         * TODO: either modularlize Elasticsearch so that we can limit the opening of this module, or find an alternative
         */
        if (JavaVersion.majorVersion(JavaVersion.CURRENT) >= 9) {
            return "--add-opens=java.base/java.io=ALL-UNNAMED";
        } else {
            return "";
        }

    }

}
