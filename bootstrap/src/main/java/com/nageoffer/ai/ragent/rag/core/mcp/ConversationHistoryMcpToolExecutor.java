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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConversationHistoryMcpToolExecutor implements McpToolExecutor {

    private static final int DEFAULT_LIMIT = 20;

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final ObjectMapper objectMapper;

    @Override
    public Tool getToolDefinition() {
        return Tool.builder()
                .name(AgentToolNames.CONVERSATION_HISTORY)
                .description("Query the current user's historical conversations or messages. Use it when the user asks about previous chats, conversation history, or past answers.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of(
                                "conversationId", Map.of(
                                        "type", "string",
                                        "description", "Optional conversation id. Omit it to list recent conversations."
                                ),
                                "keyword", Map.of(
                                        "type", "string",
                                        "description", "Optional keyword used to filter conversation titles or messages."
                                ),
                                "limit", Map.of(
                                        "type", "integer",
                                        "description", "Maximum conversations or messages to return."
                                ),
                                "order", Map.of(
                                        "type", "string",
                                        "description", "ASC or DESC by message creation time."
                                ),
                                "includeThinking", Map.of(
                                        "type", "boolean",
                                        "description", "Whether to include assistant thinking fields when present."
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
        String userId = UserContext.getUserId();
        if (!StringUtils.hasText(userId)) {
            return McpToolResultSupport.error("conversation_history requires a logged-in user context.");
        }

        Map<String, Object> safeParameters = parameters == null ? Map.of() : parameters;
        String conversationId = McpToolResultSupport.stringParam(safeParameters, "conversationId", "sessionId");
        String keyword = McpToolResultSupport.stringParam(safeParameters, "keyword", "query");
        int limit = McpToolResultSupport.intParam(safeParameters, DEFAULT_LIMIT, 1, 100, "limit", "topK");

        if (!StringUtils.hasText(conversationId)) {
            return listConversations(userId, keyword, limit);
        }
        return listMessages(userId, conversationId, keyword, limit, safeParameters);
    }

    private CallToolResult listConversations(String userId, String keyword, int limit) {
        List<Map<String, Object>> conversations = conversationService.listByUserId(userId).stream()
                .filter(item -> matchesConversation(item, keyword))
                .limit(limit)
                .map(this::conversationPayload)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", AgentToolNames.CONVERSATION_HISTORY);
        payload.put("type", "conversation_list");
        payload.put("keyword", keyword);
        payload.put("count", conversations.size());
        payload.put("conversations", conversations);
        return McpToolResultSupport.json(objectMapper, payload);
    }

    private CallToolResult listMessages(String userId, String conversationId, String keyword,
                                        int limit, Map<String, Object> parameters) {
        boolean includeThinking = McpToolResultSupport.booleanParam(parameters, false, "includeThinking");
        ConversationMessageOrder order = parseOrder(McpToolResultSupport.stringParam(parameters, "order"));
        List<Map<String, Object>> messages = conversationMessageService
                .listMessages(conversationId, userId, limit, order)
                .stream()
                .filter(item -> matchesMessage(item, keyword))
                .map(item -> messagePayload(item, includeThinking))
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", AgentToolNames.CONVERSATION_HISTORY);
        payload.put("type", "message_list");
        payload.put("conversationId", conversationId);
        payload.put("keyword", keyword);
        payload.put("order", order.name());
        payload.put("count", messages.size());
        payload.put("messages", messages);
        return McpToolResultSupport.json(objectMapper, payload);
    }

    private boolean matchesConversation(ConversationVO conversation, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        return contains(conversation.getConversationId(), needle)
                || contains(conversation.getTitle(), needle);
    }

    private boolean matchesMessage(ConversationMessageVO message, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        return contains(message.getRole(), needle)
                || contains(message.getContent(), needle)
                || contains(message.getThinkingContent(), needle);
    }

    private boolean contains(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private ConversationMessageOrder parseOrder(String order) {
        if (!StringUtils.hasText(order)) {
            return ConversationMessageOrder.ASC;
        }
        try {
            return ConversationMessageOrder.valueOf(order.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ConversationMessageOrder.ASC;
        }
    }

    private Map<String, Object> conversationPayload(ConversationVO conversation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversation.getConversationId());
        payload.put("title", conversation.getTitle());
        payload.put("lastTime", conversation.getLastTime());
        return payload;
    }

    private Map<String, Object> messagePayload(ConversationMessageVO message, boolean includeThinking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", message.getId());
        payload.put("conversationId", message.getConversationId());
        payload.put("role", message.getRole());
        payload.put("content", message.getContent());
        payload.put("createTime", message.getCreateTime());
        payload.put("vote", message.getVote());
        if (includeThinking) {
            payload.put("thinkingContent", message.getThinkingContent());
            payload.put("thinkingDuration", message.getThinkingDuration());
        }
        return payload;
    }
}
