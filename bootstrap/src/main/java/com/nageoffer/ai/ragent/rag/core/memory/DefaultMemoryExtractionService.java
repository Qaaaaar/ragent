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

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryLevel;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryScopeType;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class DefaultMemoryExtractionService implements MemoryExtractionService {

    private static final int MAX_TEXT_CHARS = 4000;

    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final RagMemoryPersistenceService memoryPersistenceService;
    private final Executor longTermMemoryExecutor;

    public DefaultMemoryExtractionService(MemoryProperties memoryProperties,
                                          LLMService llmService,
                                          ObjectMapper objectMapper,
                                          RagMemoryPersistenceService memoryPersistenceService,
                                          @Qualifier("longTermMemoryExecutor") Executor longTermMemoryExecutor) {
        this.memoryProperties = memoryProperties;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.memoryPersistenceService = memoryPersistenceService;
        this.longTermMemoryExecutor = longTermMemoryExecutor;
    }

    @Override
    public void extractAsync(MemoryExtractionContext context) {
        if (!Boolean.TRUE.equals(memoryProperties.getLongTermEnabled())
                || !Boolean.TRUE.equals(memoryProperties.getAutoExtractionEnabled())
                || context == null
                || StrUtil.isBlank(context.getUserId())
                || StrUtil.isBlank(context.getUserQuestion())) {
            return;
        }
        CompletableFuture.runAsync(() -> extract(context), longTermMemoryExecutor)
                .exceptionally(ex -> {
                    log.warn("Long-term memory extraction task failed, conversationId={}",
                            context.getConversationId(), ex);
                    return null;
                });
    }

    private void extract(MemoryExtractionContext context) {
        boolean explicit = isExplicitMemoryRequest(context.getUserQuestion());
        List<ExtractedMemory> candidates = callExtractor(context, explicit);
        if (CollUtil.isEmpty(candidates) && explicit) {
            candidates = List.of(explicitFallback(context));
        }
        if (CollUtil.isEmpty(candidates)) {
            return;
        }
        int savedCount = 0;
        for (ExtractedMemory candidate : candidates) {
            if (saveCandidate(context, candidate, explicit)) {
                savedCount++;
            }
        }
        if (explicit && savedCount == 0) {
            saveCandidate(context, explicitFallback(context), true);
        }
    }

    private List<ExtractedMemory> callExtractor(MemoryExtractionContext context, boolean explicit) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(buildSystemPrompt()),
                        ChatMessage.user(buildUserPrompt(context, explicit))
                ))
                .temperature(0.2D)
                .topP(0.9D)
                .thinking(false)
                .build();
        try {
            String response = llmService.chat(request);
            return parseCandidates(response);
        } catch (Exception e) {
            log.warn("Memory extraction LLM call failed, conversationId={}", context.getConversationId(), e);
            return List.of();
        }
    }

    private boolean saveCandidate(MemoryExtractionContext context, ExtractedMemory candidate, boolean explicit) {
        if (candidate == null || !candidate.isShouldRemember() || StrUtil.isBlank(candidate.getContent())) {
            return false;
        }
        double confidence = candidate.getConfidence() == null ? (explicit ? 1D : 0D) : candidate.getConfidence();
        if (!explicit && confidence < memoryProperties.getMinConfidence()) {
            return false;
        }
        RagMemoryScopeType scope = RagMemoryScopeType.from(candidate.getScopeType(), RagMemoryScopeType.USER);
        RagMemoryStatus status = scope == RagMemoryScopeType.ORG
                ? RagMemoryStatus.PENDING_REVIEW
                : RagMemoryStatus.ACTIVE;
        RagMemoryCommand command = RagMemoryCommand.builder()
                .scopeType(scope.name())
                .ownerUserId(scope == RagMemoryScopeType.USER ? context.getUserId() : null)
                .sourceUserId(context.getUserId())
                .memoryLevel(RagMemoryLevel.from(candidate.getMemoryLevel(), RagMemoryLevel.LONG_TERM).name())
                .memoryType(StrUtil.blankToDefault(candidate.getMemoryType(), "fact"))
                .title(StrUtil.blankToDefault(candidate.getTitle(), "Memory"))
                .content(candidate.getContent().trim())
                .keywords(candidate.getKeywords())
                .sourceConversationId(context.getConversationId())
                .sourceMessageIds(context.getSourceMessageIds())
                .confidence(confidence)
                .importance(candidate.getImportance() == null ? 50 : candidate.getImportance())
                .status(status.name())
                .operatorUserId(context.getUserId())
                .build();
        if (!memoryPersistenceService.existsActiveLike(command)) {
            memoryPersistenceService.create(command);
            return true;
        }
        return false;
    }

    private ExtractedMemory explicitFallback(MemoryExtractionContext context) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.setShouldRemember(true);
        memory.setScopeType(RagMemoryScopeType.USER.name());
        memory.setMemoryLevel(RagMemoryLevel.LONG_TERM.name());
        memory.setMemoryType("preference");
        memory.setTitle("用户显式记忆");
        memory.setContent(context.getUserQuestion());
        memory.setConfidence(1D);
        memory.setImportance(80);
        memory.setKeywords(List.of());
        return memory;
    }

    private boolean isExplicitMemoryRequest(String question) {
        String text = StrUtil.nullToEmpty(question);
        return text.contains("记住")
                || text.contains("记一下")
                || text.contains("帮我记")
                || text.contains("请记得")
                || text.contains("以后按")
                || text.contains("以后按照")
                || text.contains("以后都")
                || text.contains("以后请")
                || text.contains("以后回答")
                || text.toLowerCase().contains("remember this");
    }

    private String buildSystemPrompt() {
        return """
                你是企业知识助手的记忆抽取器。只抽取对未来对话有稳定价值的信息。
                可记忆类型包括：preference、profile、fact、procedure、terminology、task、unresolved_issue、tool_experience、feedback。
                scopeType 只能是 USER 或 ORG。仅当内容是企业通用术语、流程、制度经验时才使用 ORG，否则使用 USER。
                memoryLevel 只能是 WORKING 或 LONG_TERM。临时任务、近期问题用 WORKING；稳定偏好、事实、术语、流程用 LONG_TERM。
                不要记敏感个人信息、一次性闲聊、低置信推测。
                只输出 JSON 数组，不要输出解释。
                """;
    }

    private String buildUserPrompt(MemoryExtractionContext context, boolean explicit) {
        return """
                是否显式要求记忆：%s
                conversationId：%s

                用户问题：
                %s

                助手回答：
                %s

                输出 JSON 数组，每项字段：
                {
                  "shouldRemember": true,
                  "scopeType": "USER",
                  "memoryLevel": "LONG_TERM",
                  "memoryType": "preference",
                  "title": "短标题",
                  "content": "可复用记忆正文",
                  "keywords": ["关键词"],
                  "confidence": 0.0,
                  "importance": 0
                }
                """.formatted(
                explicit,
                StrUtil.nullToEmpty(context.getConversationId()),
                truncate(context.getUserQuestion()),
                truncate(context.getAssistantAnswer())
        );
    }

    private List<ExtractedMemory> parseCandidates(String response) {
        if (StrUtil.isBlank(response)) {
            return List.of();
        }
        try {
            String json = extractJson(response);
            JsonNode root = objectMapper.readTree(json);
            List<ExtractedMemory> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    result.add(objectMapper.treeToValue(node, ExtractedMemory.class));
                }
            } else {
                result.add(objectMapper.treeToValue(root, ExtractedMemory.class));
            }
            return result;
        } catch (Exception e) {
            log.warn("Memory extraction JSON parse failed, response={}", response);
            return List.of();
        }
    }

    private String extractJson(String response) {
        String text = response.trim();
        int arrayStart = text.indexOf('[');
        int arrayEnd = text.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return text.substring(arrayStart, arrayEnd + 1);
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1);
        }
        return text;
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) {
            return StrUtil.nullToEmpty(text);
        }
        return text.substring(0, MAX_TEXT_CHARS);
    }

    @Data
    public static class ExtractedMemory {
        private boolean shouldRemember;
        private String scopeType;
        private String memoryLevel;
        private String memoryType;
        private String title;
        private String content;
        private List<String> keywords;
        private Double confidence;
        private Integer importance;
    }
}
