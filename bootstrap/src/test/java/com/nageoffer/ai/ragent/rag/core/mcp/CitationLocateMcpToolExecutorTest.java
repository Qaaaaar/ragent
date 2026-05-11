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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CitationLocateMcpToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeCitationLocateSchema() {
        CitationLocateMcpToolExecutor executor = newExecutor();

        assertEquals(AgentToolNames.CITATION_LOCATE, executor.getToolDefinition().name());
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("quote"));
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("chunkId"));
    }

    @Test
    void shouldReturnLocatedChunkWithDocumentSource() throws Exception {
        CitationLocateMcpToolExecutor executor = newExecutor();

        CallToolResult result = executor.execute(Map.of("query", "security policy", "topK", 1));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode payload = objectMapper.readTree(text(result));
        assertEquals(AgentToolNames.CITATION_LOCATE, payload.get("tool").asText());
        assertEquals(1, payload.get("count").asInt());
        JsonNode location = payload.get("locations").get(0);
        assertEquals("chunk-1", location.get("chunkId").asText());
        assertEquals("doc-1", location.get("docId").asText());
        assertEquals(3, location.get("chunkIndex").asInt());
        assertEquals("Policy", location.get("docName").asText());
    }

    private CitationLocateMcpToolExecutor newExecutor() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);

        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(chunkMapper.selectById("chunk-1")).thenReturn(KnowledgeChunkDO.builder()
                .id("chunk-1")
                .docId("doc-1")
                .chunkIndex(3)
                .content("The security policy requires approval.")
                .enabled(1)
                .deleted(0)
                .build());
        when(documentMapper.selectById("doc-1")).thenReturn(KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .docName("Policy")
                .sourceType("file")
                .deleted(0)
                .build());
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .name("Default KB")
                .collectionName("kb_default")
                .build());
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of(RetrievedChunk.builder()
                .id("chunk-1")
                .text("The security policy requires approval.")
                .score(0.9F)
                .build()));

        RAGDefaultProperties defaultProperties = new RAGDefaultProperties();
        defaultProperties.setCollectionName("kb_default");
        SearchChannelProperties searchProperties = new SearchChannelProperties();
        searchProperties.setDefaultTopK(5);
        return new CitationLocateMcpToolExecutor(retrieverService, chunkMapper, documentMapper, knowledgeBaseMapper,
                defaultProperties, searchProperties, objectMapper);
    }

    private String text(CallToolResult result) {
        Content content = result.content().get(0);
        return content instanceof TextContent textContent ? textContent.text() : content.toString();
    }
}
