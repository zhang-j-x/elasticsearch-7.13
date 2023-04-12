/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ilm;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.xpack.core.XPackFeatureSet;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleFeatureSetUsage;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleFeatureSetUsage.PhaseStats;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleFeatureSetUsage.PolicyStats;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class IndexLifecycleFeatureSet implements XPackFeatureSet {

    private ClusterService clusterService;

    @Inject
    public IndexLifecycleFeatureSet(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @Override
    public String name() {
        return XPackField.INDEX_LIFECYCLE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> nativeCodeInfo() {
        return null;
    }

    @Override
    public void usage(ActionListener<XPackFeatureSet.Usage> listener) {
        Metadata metadata = clusterService.state().metadata();
        IndexLifecycleMetadata lifecycleMetadata = metadata.custom(IndexLifecycleMetadata.TYPE);
        if (lifecycleMetadata != null) {
            Map<String, Integer> policyUsage = new HashMap<>();
            metadata.indices().forEach(entry -> {
                String policyName = LifecycleSettings.LIFECYCLE_NAME_SETTING.get(entry.value.getSettings());
                Integer indicesManaged = policyUsage.get(policyName);
                if (indicesManaged == null) {
                    indicesManaged = 1;
                } else {
                    indicesManaged = indicesManaged + 1;
                }
                policyUsage.put(policyName, indicesManaged);
            });
            List<PolicyStats> policyStats = lifecycleMetadata.getPolicies().values().stream().map(policy -> {
                Map<String, PhaseStats> phaseStats = policy.getPhases().values().stream().map(phase -> {
                    String[] actionNames = phase.getActions().keySet().toArray(new String[phase.getActions().size()]);
                    return new Tuple<String, PhaseStats>(phase.getName(), new PhaseStats(phase.getMinimumAge(), actionNames));
                }).collect(Collectors.toMap(Tuple::v1, Tuple::v2));
                return new PolicyStats(phaseStats, policyUsage.getOrDefault(policy.getName(), 0));
            }).collect(Collectors.toList());
            listener.onResponse(new IndexLifecycleFeatureSetUsage(policyStats));
        } else {
            listener.onResponse(new IndexLifecycleFeatureSetUsage());
        }
    }

}
