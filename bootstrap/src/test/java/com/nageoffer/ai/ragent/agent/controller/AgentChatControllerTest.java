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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.controller.RAGChatController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentChatControllerTest {

    @Test
    void shouldExposeAgentChatRoutesAndDelegateToService() throws Exception {
        StubAgentChatService service = new StubAgentChatService();
        AgentChatController controller = new AgentChatController(service, defaultProperties());

        SseEmitter emitter = controller.chat("hello", "c1", true);
        controller.stop("t1");

        assertNotNull(emitter);
        assertEquals("hello", service.question);
        assertEquals("c1", service.conversationId);
        assertEquals(Boolean.TRUE, service.deepThinking);
        assertEquals("t1", service.stoppedTaskId);

        Method chat = AgentChatController.class.getMethod("chat", String.class, String.class, Boolean.class);
        Method stop = AgentChatController.class.getMethod("stop", String.class);
        assertEquals("/agent/v1/chat", chat.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/agent/v1/stop", stop.getAnnotation(PostMapping.class).value()[0]);
    }

    @Test
    void shouldKeepRagV3RoutesAsAgentCompatibilityAliases() throws Exception {
        StubAgentChatService service = new StubAgentChatService();
        RAGChatController controller = new RAGChatController(service, defaultProperties());

        SseEmitter emitter = controller.chat("hello", "c1", false);
        controller.stop("t2");

        assertNotNull(emitter);
        assertEquals("hello", service.question);
        assertEquals("c1", service.conversationId);
        assertEquals(Boolean.FALSE, service.deepThinking);
        assertEquals("t2", service.stoppedTaskId);

        Method chat = RAGChatController.class.getMethod("chat", String.class, String.class, Boolean.class);
        Method stop = RAGChatController.class.getMethod("stop", String.class);
        assertEquals("/rag/v3/chat", chat.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/rag/v3/stop", stop.getAnnotation(PostMapping.class).value()[0]);
    }

    private RAGDefaultProperties defaultProperties() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setSseTimeoutMs(1000L);
        return properties;
    }

    private static class StubAgentChatService implements AgentChatService {

        private String question;
        private String conversationId;
        private Boolean deepThinking;
        private String stoppedTaskId;

        @Override
        public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
            this.question = question;
            this.conversationId = conversationId;
            this.deepThinking = deepThinking;
        }

        @Override
        public void stopTask(String taskId) {
            this.stoppedTaskId = taskId;
        }
    }
}
