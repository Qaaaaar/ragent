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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationHistoryMcpToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldExposeConversationHistorySchema() {
        ConversationHistoryMcpToolExecutor executor = newExecutor();

        assertEquals(AgentToolNames.CONVERSATION_HISTORY, executor.getToolDefinition().name());
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("conversationId"));
        assertTrue(executor.getToolDefinition().inputSchema().properties().containsKey("keyword"));
    }

    @Test
    void shouldListConversationMessagesForCurrentUser() throws Exception {
        UserContext.set(LoginUser.builder().userId("user-1").username("alice").build());
        ConversationHistoryMcpToolExecutor executor = newExecutor();

        CallToolResult result = executor.execute(Map.of("conversationId", "conv-1", "keyword", "policy"));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode payload = objectMapper.readTree(text(result));
        assertEquals(AgentToolNames.CONVERSATION_HISTORY, payload.get("tool").asText());
        assertEquals("message_list", payload.get("type").asText());
        assertEquals(1, payload.get("count").asInt());
        assertEquals("assistant", payload.get("messages").get(0).get("role").asText());
    }

    private ConversationHistoryMcpToolExecutor newExecutor() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationMessageService conversationMessageService = mock(ConversationMessageService.class);
        when(conversationService.listByUserId("user-1")).thenReturn(List.of(ConversationVO.builder()
                .conversationId("conv-1")
                .title("Policy discussion")
                .lastTime(new Date())
                .build()));
        when(conversationMessageService.listMessages(
                eq("conv-1"), eq("user-1"), eq(20), eq(ConversationMessageOrder.ASC)
        )).thenReturn(List.of(ConversationMessageVO.builder()
                .id("msg-1")
                .conversationId("conv-1")
                .role("assistant")
                .content("We discussed the policy yesterday.")
                .createTime(new Date())
                .build()));
        return new ConversationHistoryMcpToolExecutor(conversationService, conversationMessageService, objectMapper);
    }

    private String text(CallToolResult result) {
        Content content = result.content().get(0);
        return content instanceof TextContent textContent ? textContent.text() : content.toString();
    }
}
