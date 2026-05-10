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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.core.Agent;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentResponse;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentChatServiceImpl implements AgentChatService {

    private final Agent agent;
    private final ConversationMemoryService memoryService;
    private final StreamCallbackFactory callbackFactory;
    private final StreamTaskManager taskManager;

    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId, question);

        try {
            AgentResponse response = agent.run(AgentRequest.builder()
                    .sessionId(actualConversationId)
                    .userInput(question)
                    .deepThinking(thinkingEnabled)
                    .build());
            persistUserQuestion(actualConversationId, question);
            if (!response.isSuccess()) {
                throw new IllegalStateException(StrUtil.blankToDefault(response.getErrorMessage(), "Agent execution failed"));
            }
            callback.onContent(response.getAnswer());
            callback.onComplete();
            log.info("Agent chat completed, conversationId={}, taskId={}, steps={}, toolResults={}",
                    actualConversationId, taskId, response.getSteps().size(), response.getToolResults().size());
        } catch (Exception ex) {
            log.error("Agent chat failed, conversationId={}, taskId={}", actualConversationId, taskId, ex);
            callback.onError(ex);
        }
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    private void persistUserQuestion(String conversationId, String question) {
        try {
            memoryService.append(conversationId, UserContext.getUserId(), ChatMessage.user(question));
        } catch (Exception ex) {
            log.warn("Persist Agent user question failed, conversationId={}", conversationId, ex);
        }
    }
}
