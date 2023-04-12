/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.deprecation;

import org.elasticsearch.action.admin.cluster.node.info.PluginsAndModules;
import org.elasticsearch.bootstrap.JavaVersion;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.Strings;
import org.elasticsearch.bootstrap.BootstrapSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeRoleSettings;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.transport.RemoteClusterService;
import org.elasticsearch.xpack.core.deprecation.DeprecationIssue;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.RealmSettings;
import org.elasticsearch.xpack.core.security.authc.esnative.NativeRealmSettings;
import org.elasticsearch.xpack.core.security.authc.file.FileRealmSettings;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

class NodeDeprecationChecks {

    static DeprecationIssue checkPidfile(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            Environment.PIDFILE_SETTING,
            Environment.NODE_PIDFILE_SETTING,
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.4/breaking-changes-7.4.html#deprecate-pidfile");
    }

    static DeprecationIssue checkProcessors(final Settings settings , final PluginsAndModules pluginsAndModules) {
        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            EsExecutors.PROCESSORS_SETTING,
            EsExecutors.NODE_PROCESSORS_SETTING,
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.4/breaking-changes-7.4.html#deprecate-processors");
    }

    static DeprecationIssue checkMissingRealmOrders(final Settings settings, final PluginsAndModules pluginsAndModules) {
        final Set<String> orderNotConfiguredRealms = RealmSettings.getRealmSettings(settings).entrySet()
                .stream()
                .filter(e -> false == e.getValue().hasValue(RealmSettings.ORDER_SETTING_KEY))
                .filter(e -> e.getValue().getAsBoolean(RealmSettings.ENABLED_SETTING_KEY, true))
                .map(e -> RealmSettings.realmSettingPrefix(e.getKey()) + RealmSettings.ORDER_SETTING_KEY)
                .collect(Collectors.toSet());

        if (orderNotConfiguredRealms.isEmpty()) {
            return null;
        }

        final String details = String.format(
            Locale.ROOT,
            "Found realms without order config: [%s]. In next major release, node will fail to start with missing realm order.",
            String.join("; ", orderNotConfiguredRealms));
        return new DeprecationIssue(
            DeprecationIssue.Level.CRITICAL,
            "Realm order will be required in next major release.",
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.7/breaking-changes-7.7.html#deprecate-missing-realm-order",
            details
        );
    }

    static DeprecationIssue checkUniqueRealmOrders(final Settings settings, final PluginsAndModules pluginsAndModules) {
        final Map<String, List<String>> orderToRealmSettings =
            RealmSettings.getRealmSettings(settings).entrySet()
                .stream()
                .filter(e -> e.getValue().hasValue(RealmSettings.ORDER_SETTING_KEY))
                .collect(Collectors.groupingBy(
                    e -> e.getValue().get(RealmSettings.ORDER_SETTING_KEY),
                    Collectors.mapping(e -> RealmSettings.realmSettingPrefix(e.getKey()) + RealmSettings.ORDER_SETTING_KEY,
                        Collectors.toList())));

        Set<String> duplicateOrders = orderToRealmSettings.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> entry.getKey() + ": " + entry.getValue())
            .collect(Collectors.toSet());

        if (duplicateOrders.isEmpty()) {
            return null;
        }

        final String details = String.format(
            Locale.ROOT,
            "Found multiple realms configured with the same order: [%s]. " +
                "In next major release, node will fail to start with duplicated realm order.",
            String.join("; ", duplicateOrders));

        return new DeprecationIssue(
            DeprecationIssue.Level.CRITICAL,
            "Realm orders must be unique in next major release.",
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.7/breaking-changes-7.7.html#deprecate-duplicated-realm-orders",
            details
        );
    }

    static DeprecationIssue checkImplicitlyDisabledBasicRealms(final Settings settings, final PluginsAndModules pluginsAndModules) {
        final Map<RealmConfig.RealmIdentifier, Settings> realmSettings = RealmSettings.getRealmSettings(settings);
        if (realmSettings.isEmpty()) {
            return null;
        }

        boolean anyRealmEnabled = false;
        final Set<String> unconfiguredBasicRealms =
            new HashSet<>(org.elasticsearch.common.collect.Set.of(FileRealmSettings.TYPE, NativeRealmSettings.TYPE));
        for (Map.Entry<RealmConfig.RealmIdentifier, Settings> realmSetting: realmSettings.entrySet()) {
            anyRealmEnabled = anyRealmEnabled || realmSetting.getValue().getAsBoolean(RealmSettings.ENABLED_SETTING_KEY, true);
            unconfiguredBasicRealms.remove(realmSetting.getKey().getType());
        }

        final String details;
        if (false == anyRealmEnabled) {
            final List<String> explicitlyDisabledBasicRealms =
                Sets.difference(org.elasticsearch.common.collect.Set.of(FileRealmSettings.TYPE, NativeRealmSettings.TYPE),
                    unconfiguredBasicRealms).stream().sorted().collect(Collectors.toList());
            if (explicitlyDisabledBasicRealms.isEmpty()) {
                return null;
            }
            details = String.format(
                Locale.ROOT,
                "Found explicitly disabled basic %s: [%s]. But %s will be enabled because no other realms are configured or enabled. " +
                    "In next major release, explicitly disabled basic realms will remain disabled.",
                explicitlyDisabledBasicRealms.size() == 1 ? "realm" : "realms",
                Strings.collectionToDelimitedString(explicitlyDisabledBasicRealms, ","),
                explicitlyDisabledBasicRealms.size() == 1 ? "it" : "they"
                );
        } else {
            if (unconfiguredBasicRealms.isEmpty()) {
                return null;
            }
            details = String.format(
                Locale.ROOT,
                "Found implicitly disabled basic %s: [%s]. %s disabled because there are other explicitly configured realms." +
                    "In next major release, basic realms will always be enabled unless explicitly disabled.",
                unconfiguredBasicRealms.size() == 1 ? "realm" : "realms",
                Strings.collectionToDelimitedString(unconfiguredBasicRealms, ","),
                unconfiguredBasicRealms.size() == 1 ? "It is" : "They are");
        }
        return new DeprecationIssue(
            DeprecationIssue.Level.WARNING,
            "File and/or native realms are enabled by default in next major release.",
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.13/deprecated-7.13.html#implicitly-disabled-basic-realms",
            details
        );

    }

    static DeprecationIssue checkThreadPoolListenerQueueSize(final Settings settings) {
        return checkThreadPoolListenerSetting("thread_pool.listener.queue_size", settings);
    }

    static DeprecationIssue checkThreadPoolListenerSize(final Settings settings) {
        return checkThreadPoolListenerSetting("thread_pool.listener.size", settings);
    }

    private static DeprecationIssue checkThreadPoolListenerSetting(final String name, final Settings settings) {
        final FixedExecutorBuilder builder = new FixedExecutorBuilder(settings, "listener", 1, -1, "thread_pool.listener", true);
        final List<Setting<?>> listenerSettings = builder.getRegisteredSettings();
        final Optional<Setting<?>> setting = listenerSettings.stream().filter(s -> s.getKey().equals(name)).findFirst();
        assert setting.isPresent();
        return checkRemovedSetting(
            settings,
            setting.get(),
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.x/breaking-changes-7.7.html#deprecate-listener-thread-pool");
    }

    public static DeprecationIssue checkClusterRemoteConnectSetting(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            RemoteClusterService.ENABLE_REMOTE_CLUSTERS,
            Setting.boolSetting(
                "node.remote_cluster_client",
                RemoteClusterService.ENABLE_REMOTE_CLUSTERS,
                Property.Deprecated,
                Property.NodeScope),
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.7/breaking-changes-7.7.html#deprecate-cluster-remote-connect"
        );
    }

    public static DeprecationIssue checkNodeLocalStorageSetting(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkRemovedSetting(
            settings,
            Node.NODE_LOCAL_STORAGE_SETTING,
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.8/breaking-changes-7.8.html#deprecate-node-local-storage"
        );
    }

    public static DeprecationIssue checkNodeBasicLicenseFeatureEnabledSetting(final Settings settings, Setting<?> setting) {
        return checkRemovedSetting(
            settings,
            setting,
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.8/breaking-changes-7.8.html#deprecate-basic-license-feature-enabled"
        );
    }

    public static DeprecationIssue checkGeneralScriptSizeSetting(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            ScriptService.SCRIPT_GENERAL_CACHE_SIZE_SETTING,
            ScriptService.SCRIPT_CACHE_SIZE_SETTING,
            "a script context",
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.9/breaking-changes-7.9.html#deprecate_general_script_cache_size"
        );
    }

    public static DeprecationIssue checkGeneralScriptExpireSetting(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            ScriptService.SCRIPT_GENERAL_CACHE_EXPIRE_SETTING,
            ScriptService.SCRIPT_CACHE_EXPIRE_SETTING,
            "a script context",
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.9/breaking-changes-7.9.html#deprecate_general_script_expire"
        );
    }

    public static DeprecationIssue checkGeneralScriptCompileSettings(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            ScriptService.SCRIPT_GENERAL_MAX_COMPILATIONS_RATE_SETTING,
            ScriptService.SCRIPT_MAX_COMPILATIONS_RATE_SETTING,
            "a script context",
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.9/breaking-changes-7.9.html#deprecate_general_script_compile_rate"
        );
    }

    public static DeprecationIssue checkLegacyRoleSettings(
        final Setting<Boolean> legacyRoleSetting,
        final Settings settings,
        final PluginsAndModules pluginsAndModules
    ) {

        return checkDeprecatedSetting(
            settings,
            pluginsAndModules,
            legacyRoleSetting,
            NodeRoleSettings.NODE_ROLES_SETTING,
            (v, s) -> {
                return DiscoveryNode.getRolesFromSettings(s)
                    .stream()
                    .map(DiscoveryNodeRole::roleName)
                    .collect(Collectors.joining(","));
            },
            "https://www.elastic.co/guide/en/elasticsearch/reference/master/breaking-changes-8.0.html#breaking_80_settings_changes"
        );
    }

    static DeprecationIssue checkBootstrapSystemCallFilterSetting(final Settings settings, final PluginsAndModules pluginsAndModules) {
        return checkRemovedSetting(
            settings,
            BootstrapSettings.SYSTEM_CALL_FILTER_SETTING,
            "https://www.elastic.co/guide/en/elasticsearch/reference/7.13/breaking-changes-7.13.html#deprecate-system-call-filter-setting"
        );
    }

    private static DeprecationIssue checkDeprecatedSetting(
        final Settings settings,
        final PluginsAndModules pluginsAndModules,
        final Setting<?> deprecatedSetting,
        final Setting<?> replacementSetting,
        final String url
    ) {
        return checkDeprecatedSetting(settings, pluginsAndModules, deprecatedSetting, replacementSetting, (v, s) -> v, url);
    }

    private static DeprecationIssue checkDeprecatedSetting(
        final Settings settings,
        final PluginsAndModules pluginsAndModules,
        final Setting<?> deprecatedSetting,
        final Setting<?> replacementSetting,
        final BiFunction<String, Settings, String> replacementValue,
        final String url
    ) {
        assert deprecatedSetting.isDeprecated() : deprecatedSetting;
        if (deprecatedSetting.exists(settings) == false) {
            return null;
        }
        final String deprecatedSettingKey = deprecatedSetting.getKey();
        final String replacementSettingKey = replacementSetting.getKey();
        final String value = deprecatedSetting.get(settings).toString();
        final String message = String.format(
            Locale.ROOT,
            "setting [%s] is deprecated in favor of setting [%s]",
            deprecatedSettingKey,
            replacementSettingKey);
        final String details = String.format(
            Locale.ROOT,
            "the setting [%s] is currently set to [%s], instead set [%s] to [%s]",
            deprecatedSettingKey,
            value,
            replacementSettingKey,
            replacementValue.apply(value, settings));
        return new DeprecationIssue(DeprecationIssue.Level.CRITICAL, message, url, details);
    }

    private static DeprecationIssue checkDeprecatedSetting(
        final Settings settings,
        final PluginsAndModules pluginsAndModules,
        final Setting<?> deprecatedSetting,
        final Setting.AffixSetting<?> replacementSetting,
        final String star,
        final String url
    ) {
        return checkDeprecatedSetting(settings, pluginsAndModules, deprecatedSetting, replacementSetting, (v, s) -> v, star, url);
    }

    private static DeprecationIssue checkDeprecatedSetting(
        final Settings settings,
        final PluginsAndModules pluginsAndModules,
        final Setting<?> deprecatedSetting,
        final Setting.AffixSetting<?> replacementSetting,
        final BiFunction<String, Settings, String> replacementValue,
        final String star,
        final String url
    ) {
        assert deprecatedSetting.isDeprecated() : deprecatedSetting;
        if (deprecatedSetting.exists(settings) == false) {
            return null;
        }
        final String deprecatedSettingKey = deprecatedSetting.getKey();
        final String replacementSettingKey = replacementSetting.getKey();
        final String value = deprecatedSetting.get(settings).toString();
        final String message = String.format(
            Locale.ROOT,
            "setting [%s] is deprecated in favor of grouped setting [%s]",
            deprecatedSettingKey,
            replacementSettingKey);
        final String details = String.format(
            Locale.ROOT,
            "the setting [%s] is currently set to [%s], instead set [%s] to [%s] where * is %s",
            deprecatedSettingKey,
            value,
            replacementSettingKey,
            replacementValue.apply(value, settings),
            star);
        return new DeprecationIssue(DeprecationIssue.Level.CRITICAL, message, url, details);
    }

    static DeprecationIssue checkRemovedSetting(final Settings settings, final Setting<?> removedSetting, final String url) {
        if (removedSetting.exists(settings) == false) {
            return null;
        }
        final String removedSettingKey = removedSetting.getKey();
        final String value = removedSetting.get(settings).toString();
        final String message =
            String.format(Locale.ROOT, "setting [%s] is deprecated and will be removed in the next major version", removedSettingKey);
        final String details =
            String.format(Locale.ROOT, "the setting [%s] is currently set to [%s], remove this setting", removedSettingKey, value);
        return new DeprecationIssue(DeprecationIssue.Level.CRITICAL, message, url, details);
    }

    static DeprecationIssue javaVersionCheck(Settings nodeSettings, PluginsAndModules plugins) {
        final JavaVersion javaVersion = JavaVersion.current();

        if (javaVersion.compareTo(JavaVersion.parse("11")) < 0) {
            return new DeprecationIssue(DeprecationIssue.Level.CRITICAL,
                "Java 11 is required",
                "https://www.elastic.co/guide/en/elasticsearch/reference/master/breaking-changes-8.0.html#breaking_80_packaging_changes",
                "Java 11 will be required for future versions of Elasticsearch, this node is running version ["
                    + javaVersion.toString() + "]. Consider switching to a distribution of Elasticsearch with a bundled JDK. "
                    + "If you are already using a distribution with a bundled JDK, ensure the JAVA_HOME environment variable is not set.");
        }
        return null;
    }

    static DeprecationIssue checkMultipleDataPaths(Settings nodeSettings, PluginsAndModules plugins) {
        List<String> dataPaths = Environment.PATH_DATA_SETTING.get(nodeSettings);
        if (dataPaths.size() > 1) {
            return new DeprecationIssue(DeprecationIssue.Level.CRITICAL,
                "multiple [path.data] entries are deprecated, use a single data directory",
                "https://www.elastic.co/guide/en/elasticsearch/reference/master/breaking-changes-8.0.html#breaking_80_packaging_changes",
                "Multiple data paths are deprecated. Instead, use RAID or other system level features to utilize multiple disks.");
        }
        return null;
    }

    static DeprecationIssue checkSharedDataPathSetting(final Settings settings, final PluginsAndModules pluginsAndModules) {
        if (Environment.PATH_SHARED_DATA_SETTING.exists(settings)) {
            final String message = String.format(Locale.ROOT,
                "setting [%s] is deprecated and will be removed in a future version", Environment.PATH_SHARED_DATA_SETTING.getKey());
            final String url = "https://www.elastic.co/guide/en/elasticsearch/reference/7.13/" +
                "breaking-changes-7.13.html#deprecate-shared-data-path-setting";
            final String details = "Found shared data path configured. Discontinue use of this setting.";
            return new DeprecationIssue(DeprecationIssue.Level.CRITICAL, message, url, details);
        }
        return null;
    }
}
