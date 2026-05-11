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

package com.nageoffer.ai.ragent.agent.clarification;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.memory.MemoryMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultAgentClarificationService implements AgentClarificationService {

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;

    @Override
    public Optional<String> detectClarification(AgentContext context) {
        AgentRequest request = context == null ? null : context.getRequest();
        if (request == null || StrUtil.isBlank(request.getUserInput())) {
            return Optional.empty();
        }
        try {
            RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(
                    request.getUserInput(),
                    toChatMessages(context.getMemoryMessages())
            );
            if (rewriteResult == null || StrUtil.isBlank(rewriteResult.rewrittenQuestion())) {
                return Optional.empty();
            }
            List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);
            GuidanceDecision decision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
            if (decision != null && decision.isPrompt() && StrUtil.isNotBlank(decision.getPrompt())) {
                return Optional.of(decision.getPrompt());
            }
        } catch (Exception ex) {
            log.warn("Agent clarification detection failed, sessionId={}, reason={}",
                    request.getSessionId(), ex.getMessage());
        }
        return Optional.empty();
    }

    private List<ChatMessage> toChatMessages(List<MemoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> message != null && StrUtil.isNotBlank(message.getContent()))
                .map(this::toChatMessage)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Optional<ChatMessage> toChatMessage(MemoryMessage message) {
        String role = message.getRole();
        if (MemoryMessage.ROLE_USER.equals(role)) {
            return Optional.of(ChatMessage.user(message.getContent()));
        }
        if (MemoryMessage.ROLE_ASSISTANT.equals(role)) {
            return Optional.of(ChatMessage.assistant(message.getContent()));
        }
        return Optional.empty();
    }
}
