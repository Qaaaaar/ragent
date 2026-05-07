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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryAgentMemoryTest {

    @Test
    void shouldKeepLatestMessagesPerSession() {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setMaxMessages(2);
        InMemoryAgentMemory memory = new InMemoryAgentMemory(properties);

        memory.save("s1", message("one"));
        memory.save("s1", message("two"));
        memory.save("s1", message("three"));

        List<MemoryMessage> messages = memory.load("s1", 10);

        assertEquals(2, messages.size());
        assertEquals("two", messages.get(0).getContent());
        assertEquals("three", messages.get(1).getContent());
    }

    private MemoryMessage message(String content) {
        return MemoryMessage.builder()
                .role(MemoryMessage.ROLE_USER)
                .content(content)
                .build();
    }
}
