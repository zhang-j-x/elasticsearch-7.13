/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.reindex;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.test.rest.FakeRestRequest;
import org.elasticsearch.test.rest.RestActionTestCase;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static java.util.Collections.singletonMap;

public class RestReindexActionTests extends RestActionTestCase {

    private RestReindexAction action;

    @Before
    public void setUpAction() {
        action = new RestReindexAction();
        controller().registerHandler(action);
    }

    public void testPipelineQueryParameterIsError() throws IOException {
        FakeRestRequest.Builder request = new FakeRestRequest.Builder(xContentRegistry());
        try (XContentBuilder body = JsonXContent.contentBuilder().prettyPrint()) {
            body.startObject(); {
                body.startObject("source"); {
                    body.field("index", "source");
                }
                body.endObject();
                body.startObject("dest"); {
                    body.field("index", "dest");
                }
                body.endObject();
            }
            body.endObject();
            request.withContent(BytesReference.bytes(body), body.contentType());
        }
        request.withParams(singletonMap("pipeline", "doesn't matter"));
        Exception e = expectThrows(IllegalArgumentException.class, () ->
            action.buildRequest(request.build(), new NamedWriteableRegistry(Collections.emptyList())));

        assertEquals("_reindex doesn't support [pipeline] as a query parameter. Specify it in the [dest] object instead.", e.getMessage());
    }

    public void testSetScrollTimeout() throws IOException {
        {
            FakeRestRequest.Builder requestBuilder = new FakeRestRequest.Builder(xContentRegistry());
            requestBuilder.withContent(new BytesArray("{}"), XContentType.JSON);
            ReindexRequest request = action.buildRequest(requestBuilder.build(), new NamedWriteableRegistry(Collections.emptyList()));
            assertEquals(AbstractBulkByScrollRequest.DEFAULT_SCROLL_TIMEOUT, request.getScrollTime());
        }
        {
            FakeRestRequest.Builder requestBuilder = new FakeRestRequest.Builder(xContentRegistry());
            requestBuilder.withParams(singletonMap("scroll", "10m"));
            requestBuilder.withContent(new BytesArray("{}"), XContentType.JSON);
            ReindexRequest request = action.buildRequest(requestBuilder.build(), new NamedWriteableRegistry(Collections.emptyList()));
            assertEquals("10m", request.getScrollTime().toString());
        }
    }

    /**
     * test deprecation is logged if one or more types are used in source search request inside reindex
     */
    public void testTypeInSource() throws IOException {
        FakeRestRequest.Builder requestBuilder = new FakeRestRequest.Builder(xContentRegistry())
                .withMethod(Method.POST)
                .withPath("/_reindex");
        XContentBuilder b = JsonXContent.contentBuilder().startObject();
        {
            b.startObject("source");
            {
                b.field("type", randomFrom(Arrays.asList("\"t1\"", "[\"t1\", \"t2\"]", "\"_doc\"")));
            }
            b.endObject();
        }
        b.endObject();
        requestBuilder.withContent(new BytesArray(BytesReference.bytes(b).toBytesRef()), XContentType.JSON);

        // We're not actually testing anything to do with the client, but need to set this so it doesn't fail the test for being unset.
        verifyingClient.setExecuteLocallyVerifier((arg1, arg2) -> null);

        dispatchRequest(requestBuilder.build());
        assertWarnings(ReindexRequest.TYPES_DEPRECATION_MESSAGE);
    }

    /**
     * test deprecation is logged if a type is used in the destination index request inside reindex
     */
    public void testTypeInDestination() throws IOException {
        FakeRestRequest.Builder requestBuilder = new FakeRestRequest.Builder(xContentRegistry())
                .withMethod(Method.POST)
                .withPath("/_reindex");
        XContentBuilder b = JsonXContent.contentBuilder().startObject();
        {
            b.startObject("dest");
            {
                b.field("type", (randomBoolean() ? "_doc" : randomAlphaOfLength(4)));
            }
            b.endObject();
        }
        b.endObject();
        requestBuilder.withContent(new BytesArray(BytesReference.bytes(b).toBytesRef()), XContentType.JSON);

        // We're not actually testing anything to do with the client, but need to set this so it doesn't fail the test for being unset.
        verifyingClient.setExecuteLocallyVerifier((arg1, arg2) -> null);

        dispatchRequest(requestBuilder.build());
        assertWarnings(ReindexRequest.TYPES_DEPRECATION_MESSAGE);
    }
}
