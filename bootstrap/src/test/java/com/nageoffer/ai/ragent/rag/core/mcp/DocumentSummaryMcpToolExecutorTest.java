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
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
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

class DocumentSummaryMcpToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeDocumentSummarySchema() {
        DocumentSummaryMcpToolExecutor executor = newExecutor("summary");

        assertEquals(AgentToolNames.DOCUMENT_SUMMARY, executor.getToolDefinition().name());
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("docId"));
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("documentName"));
    }

    @Test
    void shouldSummarizeDocumentChunks() throws Exception {
        DocumentSummaryMcpToolExecutor executor = newExecutor("LLM summary");

        CallToolResult result = executor.execute(Map.of("docId", "doc-1", "focus", "security"));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode payload = objectMapper.readTree(text(result));
        assertEquals(AgentToolNames.DOCUMENT_SUMMARY, payload.get("tool").asText());
        assertEquals("doc-1", payload.get("document").get("docId").asText());
        assertEquals("Policy", payload.get("document").get("docName").asText());
        assertEquals("LLM summary", payload.get("summary").asText());
        assertEquals("chunk-1", payload.get("supportingChunks").get(0).get("chunkId").asText());
    }

    private DocumentSummaryMcpToolExecutor newExecutor(String llmResponse) {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        LLMService llmService = mock(LLMService.class);

        when(documentMapper.selectById("doc-1")).thenReturn(KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .docName("Policy")
                .sourceType("file")
                .chunkCount(1)
                .deleted(0)
                .build());
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .name("Default KB")
                .collectionName("kb_default")
                .build());
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(KnowledgeChunkDO.builder()
                .id("chunk-1")
                .docId("doc-1")
                .chunkIndex(0)
                .content("Document chunk content")
                .enabled(1)
                .deleted(0)
                .build()));
        when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

        return new DocumentSummaryMcpToolExecutor(documentMapper, knowledgeBaseMapper, chunkMapper, llmService, objectMapper);
    }

    private String text(CallToolResult result) {
        Content content = result.content().get(0);
        return content instanceof TextContent textContent ? textContent.text() : content.toString();
    }
}
