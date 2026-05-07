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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void shouldLoadToolsFromMcpRegistry() {
        McpToolExecutor executor = new StubMcpToolExecutor("weather");
        ToolRegistry registry = new ToolRegistry(new StubMcpToolRegistry(List.of(executor)));

        registry.init();

        assertEquals(1, registry.size());
        assertTrue(registry.findByName("weather").isPresent());
        assertEquals("weather", registry.listTools().get(0).name());
        assertTrue(registry.describeTools().get(0).contains("weather"));
    }

    static class StubMcpToolExecutor implements McpToolExecutor {

        private final Tool tool;

        StubMcpToolExecutor(String name) {
            this.tool = Tool.builder()
                    .name(name)
                    .description("weather lookup")
                    .inputSchema(new JsonSchema("object", Map.of("city", Map.of("type", "string")), List.of("city"), false, null, null))
                    .build();
        }

        @Override
        public Tool getToolDefinition() {
            return tool;
        }

        @Override
        public CallToolResult execute(Map<String, Object> parameters) {
            return CallToolResult.builder().addTextContent("ok").build();
        }
    }

    static class StubMcpToolRegistry implements McpToolRegistry {

        private final List<McpToolExecutor> executors;

        StubMcpToolRegistry(List<McpToolExecutor> executors) {
            this.executors = executors;
        }

        @Override
        public void register(McpToolExecutor executor) {
        }

        @Override
        public void unregister(String toolId) {
        }

        @Override
        public Optional<McpToolExecutor> getExecutor(String toolId) {
            return executors.stream().filter(executor -> executor.getToolId().equals(toolId)).findFirst();
        }

        @Override
        public List<Tool> listAllTools() {
            return executors.stream().map(McpToolExecutor::getToolDefinition).toList();
        }

        @Override
        public List<McpToolExecutor> listAllExecutors() {
            return executors;
        }

        @Override
        public boolean contains(String toolId) {
            return getExecutor(toolId).isPresent();
        }

        @Override
        public int size() {
            return executors.size();
        }
    }
}
