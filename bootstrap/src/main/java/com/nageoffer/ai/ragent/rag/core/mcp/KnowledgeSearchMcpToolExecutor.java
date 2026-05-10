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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchMcpToolExecutor implements McpToolExecutor {

    private final RetrieverService retrieverService;
    private final RAGDefaultProperties defaultProperties;
    private final SearchChannelProperties searchProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Tool getToolDefinition() {
        return Tool.builder()
                .name(AgentToolNames.KNOWLEDGE_SEARCH)
                .description("Search the enterprise knowledge base for grounding evidence, document chunks, policies, manuals, citations, and project-specific facts.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Natural language search query."
                                ),
                                "topK", Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of chunks to return."
                                ),
                                "collectionName", Map.of(
                                        "type", "string",
                                        "description", "Optional target vector collection."
                                ),
                                "metadataFilters", Map.of(
                                        "type", "object",
                                        "description", "Optional metadata equality filters."
                                )
                        ),
                        List.of("query"),
                        false,
                        null,
                        null
                ))
                .build();
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        Map<String, Object> safeParameters = parameters == null ? Map.of() : parameters;
        String query = resolveQuery(safeParameters);
        if (!StringUtils.hasText(query)) {
            return errorResult("knowledge_search requires a non-empty query.");
        }

        int topK = resolveTopK(safeParameters);
        String collectionName = resolveCollectionName(safeParameters);
        Map<String, Object> metadataFilters = resolveMetadataFilters(safeParameters);

        try {
            List<RetrievedChunk> chunks = retrieverService.retrieve(RetrieveRequest.builder()
                    .query(query)
                    .topK(topK)
                    .collectionName(collectionName)
                    .metadataFilters(metadataFilters)
                    .build());
            return successResult(toPayload(query, topK, collectionName, metadataFilters, chunks));
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("knowledge_search failed, collectionName={}, topK={}, reason={}", collectionName, topK, reason);
            return errorResult("knowledge_search failed: " + reason);
        }
    }

    private String resolveQuery(Map<String, Object> parameters) {
        Object value = parameters.get("query");
        if (value == null) {
            value = parameters.get("input");
        }
        return value == null ? null : String.valueOf(value).trim();
    }

    private int resolveTopK(Map<String, Object> parameters) {
        Object value = parameters.get("topK");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Math.max(1, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return defaultTopK();
            }
        }
        return defaultTopK();
    }

    private int defaultTopK() {
        return Math.max(1, searchProperties.getDefaultTopK());
    }

    private String resolveCollectionName(Map<String, Object> parameters) {
        Object value = parameters.get("collectionName");
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return defaultProperties.getCollectionName();
    }

    private Map<String, Object> resolveMetadataFilters(Map<String, Object> parameters) {
        Object value = parameters.get("metadataFilters");
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        source.forEach((key, filterValue) -> {
            if (key != null) {
                filters.put(String.valueOf(key), filterValue);
            }
        });
        return filters;
    }

    private Map<String, Object> toPayload(String query, int topK, String collectionName,
                                          Map<String, Object> metadataFilters,
                                          List<RetrievedChunk> chunks) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", AgentToolNames.KNOWLEDGE_SEARCH);
        payload.put("query", query);
        payload.put("topK", topK);
        payload.put("collectionName", collectionName);
        payload.put("metadataFilters", metadataFilters);
        payload.put("count", chunks == null ? 0 : chunks.size());
        payload.put("chunks", chunks == null ? List.of() : chunks.stream()
                .map(chunk -> toChunkPayload(chunk, collectionName))
                .toList());
        return payload;
    }

    private Map<String, Object> toChunkPayload(RetrievedChunk chunk, String collectionName) {
        Map<String, Object> metadata = new HashMap<>();
        if (StringUtils.hasText(collectionName)) {
            metadata.put("collectionName", collectionName);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", chunk.getId());
        item.put("source", collectionName);
        item.put("score", chunk.getScore());
        item.put("content", chunk.getText());
        item.put("metadata", metadata);
        return item;
    }

    private CallToolResult successResult(Map<String, Object> payload) {
        try {
            return CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (JsonProcessingException ex) {
            return errorResult("knowledge_search result serialization failed: " + ex.getMessage());
        }
    }

    private CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }
}
