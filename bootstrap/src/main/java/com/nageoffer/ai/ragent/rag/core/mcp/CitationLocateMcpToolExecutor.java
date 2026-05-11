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

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CitationLocateMcpToolExecutor implements McpToolExecutor {

    private final RetrieverService retrieverService;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RAGDefaultProperties defaultProperties;
    private final SearchChannelProperties searchProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Tool getToolDefinition() {
        return Tool.builder()
                .name(AgentToolNames.CITATION_LOCATE)
                .description("Locate citations in knowledge-base chunks. Use it to find docId, chunkId, chunkIndex, source, and surrounding evidence for a quote or claim.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Quote, claim, or natural-language citation query."
                                ),
                                "quote", Map.of(
                                        "type", "string",
                                        "description", "Exact quoted text to locate."
                                ),
                                "docId", Map.of(
                                        "type", "string",
                                        "description", "Optional document id filter."
                                ),
                                "chunkId", Map.of(
                                        "type", "string",
                                        "description", "Optional exact chunk id."
                                ),
                                "collectionName", Map.of(
                                        "type", "string",
                                        "description", "Optional target vector collection."
                                ),
                                "topK", Map.of(
                                        "type", "integer",
                                        "description", "Maximum locations to return."
                                ),
                                "exactOnly", Map.of(
                                        "type", "boolean",
                                        "description", "When true, skip semantic fallback and only run exact/LIKE matching."
                                )
                        ),
                        List.of(),
                        false,
                        null,
                        null
                ))
                .build();
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        Map<String, Object> safeParameters = parameters == null ? Map.of() : parameters;
        String searchText = firstText(
                McpToolResultSupport.stringParam(safeParameters, "quote", "text"),
                McpToolResultSupport.stringParam(safeParameters, "query", "claim")
        );
        String docId = McpToolResultSupport.stringParam(safeParameters, "docId", "documentId");
        String chunkId = McpToolResultSupport.stringParam(safeParameters, "chunkId");
        int topK = McpToolResultSupport.intParam(safeParameters, searchProperties.getDefaultTopK(), 1, 50, "topK", "limit");
        boolean exactOnly = McpToolResultSupport.booleanParam(safeParameters, false, "exactOnly");

        if (!StringUtils.hasText(searchText) && !StringUtils.hasText(chunkId)) {
            return McpToolResultSupport.error("citation_locate requires query/quote or chunkId.");
        }

        LinkedHashMap<String, LocationHit> hits = new LinkedHashMap<>();
        addChunkIdHit(hits, chunkId, docId);
        if (StringUtils.hasText(searchText) && hits.size() < topK) {
            addDirectHits(hits, searchText, docId, topK);
        }
        if (!exactOnly && StringUtils.hasText(searchText) && hits.size() < topK) {
            addSemanticHits(hits, searchText, docId, resolveCollectionName(safeParameters, docId), topK);
        }

        List<Map<String, Object>> locations = hits.values().stream()
                .limit(topK)
                .map(hit -> toLocationPayload(hit, searchText))
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", AgentToolNames.CITATION_LOCATE);
        payload.put("query", searchText);
        payload.put("docId", docId);
        payload.put("chunkId", chunkId);
        payload.put("topK", topK);
        payload.put("exactOnly", exactOnly);
        payload.put("count", locations.size());
        payload.put("locations", locations);
        return McpToolResultSupport.json(objectMapper, payload);
    }

    private void addChunkIdHit(Map<String, LocationHit> hits, String chunkId, String docId) {
        if (!StringUtils.hasText(chunkId)) {
            return;
        }
        KnowledgeChunkDO chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || (StringUtils.hasText(docId) && !docId.equals(chunk.getDocId()))) {
            return;
        }
        hits.put(chunk.getId(), new LocationHit(chunk, 1.0F, "chunk_id"));
    }

    private void addDirectHits(Map<String, LocationHit> hits, String searchText, String docId, int topK) {
        List<KnowledgeChunkDO> chunks = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(StringUtils.hasText(docId), KnowledgeChunkDO::getDocId, docId)
                        .eq(KnowledgeChunkDO::getEnabled, 1)
                        .eq(KnowledgeChunkDO::getDeleted, 0)
                        .like(KnowledgeChunkDO::getContent, searchText)
                        .orderByAsc(KnowledgeChunkDO::getDocId)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
                        .last("limit " + topK)
        );
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        for (KnowledgeChunkDO chunk : chunks) {
            hits.putIfAbsent(chunk.getId(), new LocationHit(chunk, 1.0F, "exact_text"));
        }
    }

    private void addSemanticHits(Map<String, LocationHit> hits, String searchText, String docId,
                                 String collectionName, int topK) {
        try {
            List<RetrievedChunk> retrieved = retrieverService.retrieve(RetrieveRequest.builder()
                    .query(searchText)
                    .collectionName(collectionName)
                    .topK(Math.max(topK, searchProperties.getDefaultTopK()))
                    .metadataFilters(StringUtils.hasText(docId) ? Map.of("doc_id", docId) : Map.of())
                    .build());
            if (CollUtil.isEmpty(retrieved)) {
                return;
            }
            for (RetrievedChunk item : retrieved) {
                if (item == null || !StringUtils.hasText(item.getId()) || hits.containsKey(item.getId())) {
                    continue;
                }
                KnowledgeChunkDO chunk = chunkMapper.selectById(item.getId());
                if (chunk == null) {
                    hits.put(item.getId(), new LocationHit(fallbackChunk(item), item.getScore(), "semantic"));
                    continue;
                }
                if (StringUtils.hasText(docId) && !docId.equals(chunk.getDocId())) {
                    continue;
                }
                hits.put(chunk.getId(), new LocationHit(chunk, item.getScore(), "semantic"));
                if (hits.size() >= topK) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("citation_locate semantic search failed, collectionName={}, reason={}", collectionName, ex.getMessage());
        }
    }

    private String resolveCollectionName(Map<String, Object> parameters, String docId) {
        String collectionName = McpToolResultSupport.stringParam(parameters, "collectionName");
        if (StringUtils.hasText(collectionName)) {
            return collectionName;
        }
        if (StringUtils.hasText(docId)) {
            KnowledgeDocumentDO document = documentMapper.selectById(docId);
            if (document != null && StringUtils.hasText(document.getKbId())) {
                KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(document.getKbId());
                if (knowledgeBase != null && StringUtils.hasText(knowledgeBase.getCollectionName())) {
                    return knowledgeBase.getCollectionName();
                }
            }
        }
        return defaultProperties.getCollectionName();
    }

    private Map<String, Object> toLocationPayload(LocationHit hit, String searchText) {
        KnowledgeChunkDO chunk = hit.chunk();
        KnowledgeDocumentDO document = StringUtils.hasText(chunk.getDocId())
                ? documentMapper.selectById(chunk.getDocId())
                : null;
        KnowledgeBaseDO knowledgeBase = document != null && StringUtils.hasText(document.getKbId())
                ? knowledgeBaseMapper.selectById(document.getKbId())
                : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchType", hit.matchType());
        payload.put("score", hit.score());
        payload.put("chunkId", chunk.getId());
        payload.put("docId", chunk.getDocId());
        payload.put("chunkIndex", chunk.getChunkIndex());
        payload.put("docName", document == null ? null : document.getDocName());
        payload.put("kbId", document == null ? null : document.getKbId());
        payload.put("kbName", knowledgeBase == null ? null : knowledgeBase.getName());
        payload.put("collectionName", knowledgeBase == null ? null : knowledgeBase.getCollectionName());
        payload.put("sourceType", document == null ? null : document.getSourceType());
        payload.put("sourceLocation", document == null ? null : document.getSourceLocation());
        payload.put("snippet", McpToolResultSupport.snippetAround(chunk.getContent(), searchText, 500));
        payload.put("content", McpToolResultSupport.truncate(chunk.getContent(), 1_200));
        return payload;
    }

    private KnowledgeChunkDO fallbackChunk(RetrievedChunk item) {
        return KnowledgeChunkDO.builder()
                .id(item.getId())
                .content(item.getText())
                .build();
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private record LocationHit(KnowledgeChunkDO chunk, Float score, String matchType) {
    }
}
