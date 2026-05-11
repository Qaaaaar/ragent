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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentSummaryMcpToolExecutor implements McpToolExecutor {

    private static final int DEFAULT_MAX_CHUNKS = 24;
    private static final int MAX_SOURCE_CHARS = 16_000;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Override
    public Tool getToolDefinition() {
        return Tool.builder()
                .name(AgentToolNames.DOCUMENT_SUMMARY)
                .description("Summarize a knowledge-base document by docId or documentName, returning a concise summary plus supporting chunk references.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of(
                                "docId", Map.of(
                                        "type", "string",
                                        "description", "Optional document id. Prefer this when known."
                                ),
                                "documentName", Map.of(
                                        "type", "string",
                                        "description", "Optional document title/name to resolve when docId is unknown."
                                ),
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Optional focus question or fallback document-name search text."
                                ),
                                "focus", Map.of(
                                        "type", "string",
                                        "description", "Optional focus area for the summary."
                                ),
                                "maxChunks", Map.of(
                                        "type", "integer",
                                        "description", "Maximum document chunks to read."
                                ),
                                "maxChars", Map.of(
                                        "type", "integer",
                                        "description", "Target maximum summary length in Chinese characters."
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
        DocumentResolution resolution = resolveDocument(safeParameters);
        if (resolution.error() != null) {
            return McpToolResultSupport.errorJson(objectMapper, resolution.toPayload(), resolution.error());
        }

        int maxChunks = McpToolResultSupport.intParam(safeParameters, DEFAULT_MAX_CHUNKS, 1, 100, "maxChunks", "limit");
        int maxChars = McpToolResultSupport.intParam(safeParameters, 800, 200, 4_000, "maxChars", "summaryMaxChars");
        String focus = firstText(
                McpToolResultSupport.stringParam(safeParameters, "focus"),
                McpToolResultSupport.stringParam(safeParameters, "query")
        );

        KnowledgeDocumentDO document = resolution.document();
        List<KnowledgeChunkDO> chunks = listDocumentChunks(document.getId(), maxChunks);
        if (CollUtil.isEmpty(chunks)) {
            return McpToolResultSupport.error("document_summary found no enabled chunks for docId=" + document.getId());
        }

        KnowledgeBaseDO knowledgeBase = StringUtils.hasText(document.getKbId())
                ? knowledgeBaseMapper.selectById(document.getKbId())
                : null;
        String summary = summarize(document, chunks, focus, maxChars);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", AgentToolNames.DOCUMENT_SUMMARY);
        payload.put("document", documentPayload(document, knowledgeBase));
        payload.put("focus", focus);
        payload.put("usedChunks", chunks.size());
        payload.put("summary", summary);
        payload.put("supportingChunks", chunks.stream().limit(12).map(this::chunkReference).toList());
        return McpToolResultSupport.json(objectMapper, payload);
    }

    private DocumentResolution resolveDocument(Map<String, Object> parameters) {
        String docId = McpToolResultSupport.stringParam(parameters, "docId", "documentId");
        if (StringUtils.hasText(docId)) {
            KnowledgeDocumentDO document = documentMapper.selectById(docId);
            if (document == null || Integer.valueOf(1).equals(document.getDeleted())) {
                return DocumentResolution.error("document_summary could not find docId=" + docId, List.of());
            }
            return DocumentResolution.found(document);
        }

        String keyword = firstText(
                McpToolResultSupport.stringParam(parameters, "documentName", "docName", "name", "title"),
                McpToolResultSupport.stringParam(parameters, "query")
        );
        if (!StringUtils.hasText(keyword)) {
            return DocumentResolution.error("document_summary requires docId or documentName/query.", List.of());
        }

        List<KnowledgeDocumentDO> candidates = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .like(KnowledgeDocumentDO::getDocName, keyword)
                        .orderByDesc(KnowledgeDocumentDO::getUpdateTime)
                        .last("limit 6")
        );
        if (CollUtil.isEmpty(candidates)) {
            return DocumentResolution.error("document_summary could not resolve documentName=" + keyword, List.of());
        }
        List<KnowledgeDocumentDO> exact = candidates.stream()
                .filter(item -> keyword.equalsIgnoreCase(item.getDocName()))
                .toList();
        if (exact.size() == 1) {
            return DocumentResolution.found(exact.get(0));
        }
        if (candidates.size() == 1) {
            return DocumentResolution.found(candidates.get(0));
        }
        return DocumentResolution.error("document_summary found multiple matching documents. Provide docId.", candidates);
    }

    private List<KnowledgeChunkDO> listDocumentChunks(String docId, int maxChunks) {
        return chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .eq(KnowledgeChunkDO::getEnabled, 1)
                        .eq(KnowledgeChunkDO::getDeleted, 0)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
                        .last("limit " + maxChunks)
        );
    }

    private String summarize(KnowledgeDocumentDO document, List<KnowledgeChunkDO> chunks, String focus, int maxChars) {
        String sourceText = buildSourceText(chunks);
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You summarize enterprise knowledge-base documents. Be faithful to the provided chunks and do not invent facts."),
                ChatMessage.user("""
                        Document name: %s
                        Focus: %s
                        Target length: <= %d Chinese characters.

                        Write the answer in the user's language. Include the main points, important constraints, and actionable conclusions. If the evidence is insufficient, say so briefly.

                        Document chunks:
                        %s
                        """.formatted(
                        document.getDocName(),
                        StringUtils.hasText(focus) ? focus : "(general summary)",
                        maxChars,
                        sourceText
                ))
        );
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.2D)
                    .topP(0.8D)
                    .maxTokens(Math.max(256, maxChars * 2))
                    .thinking(false)
                    .build();
            String summary = llmService.chat(request);
            return McpToolResultSupport.truncate(summary == null ? "" : summary.trim(), maxChars + 800);
        } catch (Exception ex) {
            log.warn("document_summary LLM call failed, docId={}, reason={}", document.getId(), ex.getMessage());
            return fallbackSummary(chunks, maxChars);
        }
    }

    private String buildSourceText(List<KnowledgeChunkDO> chunks) {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeChunkDO chunk : chunks) {
            if (!StringUtils.hasText(chunk.getContent())) {
                continue;
            }
            builder.append("[chunkId=")
                    .append(chunk.getId())
                    .append(", chunkIndex=")
                    .append(chunk.getChunkIndex())
                    .append("]\n")
                    .append(chunk.getContent())
                    .append("\n\n");
            if (builder.length() >= MAX_SOURCE_CHARS) {
                break;
            }
        }
        return McpToolResultSupport.truncate(builder.toString().trim(), MAX_SOURCE_CHARS);
    }

    private String fallbackSummary(List<KnowledgeChunkDO> chunks, int maxChars) {
        String joined = chunks.stream()
                .map(KnowledgeChunkDO::getContent)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
        return McpToolResultSupport.truncate(joined, maxChars);
    }

    private Map<String, Object> documentPayload(KnowledgeDocumentDO document, KnowledgeBaseDO knowledgeBase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("docId", document.getId());
        payload.put("docName", document.getDocName());
        payload.put("kbId", document.getKbId());
        payload.put("kbName", knowledgeBase == null ? null : knowledgeBase.getName());
        payload.put("collectionName", knowledgeBase == null ? null : knowledgeBase.getCollectionName());
        payload.put("sourceType", document.getSourceType());
        payload.put("sourceLocation", document.getSourceLocation());
        payload.put("fileType", document.getFileType());
        payload.put("chunkCount", document.getChunkCount());
        return payload;
    }

    private Map<String, Object> chunkReference(KnowledgeChunkDO chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkId", chunk.getId());
        payload.put("docId", chunk.getDocId());
        payload.put("chunkIndex", chunk.getChunkIndex());
        payload.put("preview", McpToolResultSupport.truncate(chunk.getContent(), 240));
        return payload;
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private record DocumentResolution(KnowledgeDocumentDO document, List<KnowledgeDocumentDO> candidates, String error) {

        static DocumentResolution found(KnowledgeDocumentDO document) {
            return new DocumentResolution(document, List.of(), null);
        }

        static DocumentResolution error(String error, List<KnowledgeDocumentDO> candidates) {
            return new DocumentResolution(null, candidates == null ? List.of() : candidates, error);
        }

        Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", AgentToolNames.DOCUMENT_SUMMARY);
            payload.put("error", error);
            payload.put("candidates", candidates.stream().map(item -> {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("docId", item.getId());
                candidate.put("docName", item.getDocName());
                candidate.put("kbId", item.getKbId());
                candidate.put("sourceType", item.getSourceType());
                candidate.put("sourceLocation", item.getSourceLocation());
                return candidate;
            }).toList());
            return payload;
        }
    }
}
