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

package com.nageoffer.ai.ragent.rag.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchMcpToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeKnowledgeSearchToolSchema() {
        KnowledgeSearchMcpToolExecutor executor = newExecutor(new StubRetrieverService());

        assertEquals(AgentToolNames.KNOWLEDGE_SEARCH, executor.getToolDefinition().name());
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("query"));
        assertTrue(executor.getToolDefinition().inputSchema().required().contains("query"));
    }

    @Test
    void shouldUseDefaultsAndReturnChunksAsTextPayload() throws Exception {
        StubRetrieverService retrieverService = new StubRetrieverService();
        KnowledgeSearchMcpToolExecutor executor = newExecutor(retrieverService);

        CallToolResult result = executor.execute(Map.of("query", "policy"));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals("policy", retrieverService.lastRequest.getQuery());
        assertEquals(7, retrieverService.lastRequest.getTopK());
        assertEquals("kb_default", retrieverService.lastRequest.getCollectionName());

        JsonNode payload = objectMapper.readTree(text(result));
        assertEquals(AgentToolNames.KNOWLEDGE_SEARCH, payload.get("tool").asText());
        assertEquals(1, payload.get("count").asInt());
        assertEquals("doc-1", payload.get("chunks").get(0).get("id").asText());
        assertEquals("kb_default", payload.get("chunks").get(0).get("source").asText());
        assertEquals("chunk text", payload.get("chunks").get(0).get("content").asText());
        assertEquals("kb_default", payload.get("chunks").get(0).get("metadata").get("collectionName").asText());
    }

    @Test
    void shouldHonorCollectionTopKAndMetadataFilters() {
        StubRetrieverService retrieverService = new StubRetrieverService();
        KnowledgeSearchMcpToolExecutor executor = newExecutor(retrieverService);

        executor.execute(Map.of(
                "query", "invoice",
                "topK", "3",
                "collectionName", "finance",
                "metadataFilters", Map.of("biz", "invoice")
        ));

        assertEquals(3, retrieverService.lastRequest.getTopK());
        assertEquals("finance", retrieverService.lastRequest.getCollectionName());
        assertEquals("invoice", retrieverService.lastRequest.getMetadataFilters().get("biz"));
    }

    private KnowledgeSearchMcpToolExecutor newExecutor(RetrieverService retrieverService) {
        RAGDefaultProperties defaultProperties = new RAGDefaultProperties();
        defaultProperties.setCollectionName("kb_default");
        SearchChannelProperties searchProperties = new SearchChannelProperties();
        searchProperties.setDefaultTopK(7);
        return new KnowledgeSearchMcpToolExecutor(retrieverService, defaultProperties, searchProperties, objectMapper);
    }

    private String text(CallToolResult result) {
        Content content = result.content().get(0);
        return content instanceof TextContent textContent ? textContent.text() : content.toString();
    }

    private static class StubRetrieverService implements RetrieverService {

        private RetrieveRequest lastRequest;

        @Override
        public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
            this.lastRequest = retrieveParam;
            return List.of(RetrievedChunk.builder()
                    .id("doc-1")
                    .text("chunk text")
                    .score(0.88F)
                    .build());
        }

        @Override
        public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
            return List.of();
        }
    }
}
