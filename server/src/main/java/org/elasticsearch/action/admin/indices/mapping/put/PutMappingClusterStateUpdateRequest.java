/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.mapping.put;

import org.elasticsearch.cluster.ack.IndicesClusterStateUpdateRequest;

/**
 * Cluster state update request that allows to put a mapping
 */
public class PutMappingClusterStateUpdateRequest extends IndicesClusterStateUpdateRequest<PutMappingClusterStateUpdateRequest> {

    private String type;

    private String source;

    public PutMappingClusterStateUpdateRequest() {

    }

    public String type() {
        return type;
    }

    public PutMappingClusterStateUpdateRequest type(String type) {
        this.type = type;
        return this;
    }

    public String source() {
        return source;
    }

    public PutMappingClusterStateUpdateRequest source(String source) {
        this.source = source;
        return this;
    }
}
