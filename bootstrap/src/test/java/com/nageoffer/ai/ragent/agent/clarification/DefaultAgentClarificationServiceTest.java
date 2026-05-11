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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentClarificationServiceTest {

    @Test
    void shouldReturnGuidancePromptForAmbiguousIntent() {
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        DefaultAgentClarificationService service = new DefaultAgentClarificationService(
                rewriteService, intentResolver, guidanceService);
        RewriteResult rewriteResult = new RewriteResult("query process", List.of("query process"));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("query process", List.of()));
        when(rewriteService.rewriteWithSplit(any(), any())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("query process", subIntents))
                .thenReturn(GuidanceDecision.prompt("Please choose a system."));

        Optional<String> result = service.detectClarification(context("query process"));

        assertTrue(result.isPresent());
        assertEquals("Please choose a system.", result.get());
    }

    @Test
    void shouldPassUserAndAssistantMemoryToRewrite() {
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        DefaultAgentClarificationService service = new DefaultAgentClarificationService(
                rewriteService, intentResolver, guidanceService);
        RewriteResult rewriteResult = new RewriteResult("query process", List.of("query process"));
        when(rewriteService.rewriteWithSplit(any(), any())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(List.of());
        when(guidanceService.detectAmbiguity("query process", List.of())).thenReturn(GuidanceDecision.none());
        AgentContext context = context("query process");
        context.getMemoryMessages().add(MemoryMessage.builder()
                .role(MemoryMessage.ROLE_USER)
                .content("previous question")
                .build());
        context.getMemoryMessages().add(MemoryMessage.builder()
                .role(MemoryMessage.ROLE_ASSISTANT)
                .content("previous answer")
                .build());
        context.getMemoryMessages().add(MemoryMessage.builder()
                .role(MemoryMessage.ROLE_TOOL)
                .content("tool result")
                .build());

        service.detectClarification(context);

        ArgumentCaptor<List<ChatMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(rewriteService).rewriteWithSplit(any(), historyCaptor.capture());
        List<ChatMessage> history = historyCaptor.getValue();
        assertEquals(2, history.size());
        assertEquals(ChatMessage.Role.USER, history.get(0).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, history.get(1).getRole());
    }

    @Test
    void shouldReturnEmptyWhenRewriteIsBlank() {
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        DefaultAgentClarificationService service = new DefaultAgentClarificationService(
                rewriteService, intentResolver, guidanceService);
        when(rewriteService.rewriteWithSplit(any(), any())).thenReturn(new RewriteResult("", List.of()));

        Optional<String> result = service.detectClarification(context("query process"));

        assertTrue(result.isEmpty());
        verify(intentResolver, never()).resolve(any());
        verify(guidanceService, never()).detectAmbiguity(any(), any());
    }

    private AgentContext context(String userInput) {
        return AgentContext.builder()
                .request(AgentRequest.builder()
                        .userInput(userInput)
                        .sessionId("s1")
                        .build())
                .build();
    }
}
