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

package com.nageoffer.ai.ragent.rag.core.router;

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultChatRoutePlannerTest {

    private final DefaultChatRoutePlanner planner = new DefaultChatRoutePlanner();

    @Test
    void shouldRouteIdentityGreetingToDirectSystem() {
        ChatRoutePlan plan = planner.plan(context("\u4f60\u597d\uff0c\u4f60\u662f\u8c01\uff1f", List.of()));

        assertEquals(ChatRoute.DIRECT_SYSTEM, plan.route());
    }

    @Test
    void shouldKeepRetrievalWhenGreetingContainsRetrievalHints() {
        ChatRoutePlan plan = planner.plan(context(
                "\u4f60\u597d\uff0c\u5e2e\u6211\u67e5\u8be2\u8003\u52e4\u5236\u5ea6\u6587\u6863",
                List.of()
        ));

        assertEquals(ChatRoute.RAG_RETRIEVAL, plan.route());
    }

    @Test
    void shouldRouteSystemIntentToDirectSystemWithCustomPrompt() {
        IntentNode node = IntentNode.builder()
                .kind(IntentKind.SYSTEM)
                .promptTemplate("custom system prompt")
                .build();
        NodeScore nodeScore = NodeScore.builder().node(node).score(1D).build();
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("hello", List.of(nodeScore)));

        ChatRoutePlan plan = planner.plan(context("hello", subIntents));

        assertEquals(ChatRoute.DIRECT_SYSTEM, plan.route());
        assertEquals("custom system prompt", plan.customPrompt());
    }

    private StreamChatContext context(String question, List<SubQuestionIntent> subIntents) {
        StreamChatContext context = StreamChatContext.builder()
                .question(question)
                .conversationId("c1")
                .taskId("t1")
                .userId("u1")
                .build();
        context.setRewriteResult(new RewriteResult(question, List.of(question)));
        context.setSubIntents(subIntents);
        return context;
    }
}
