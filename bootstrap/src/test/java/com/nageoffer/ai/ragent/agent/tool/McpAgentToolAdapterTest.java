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
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAgentToolAdapterTest {

    @Test
    void shouldForwardCallToMcpExecutor() {
        StubExecutor executor = new StubExecutor();
        McpAgentToolAdapter adapter = new McpAgentToolAdapter(executor);

        ToolCallResult result = adapter.call(Map.of("city", "Hangzhou"));

        assertTrue(result.isSuccess());
        assertEquals("weather", result.getToolName());
        assertEquals("Hangzhou", executor.lastArguments.get("city"));
        assertTrue(result.getRawResult().contains("sunny"));
        assertEquals("object", adapter.schema().get("type"));
    }

    private static class StubExecutor implements McpToolExecutor {

        private Map<String, Object> lastArguments;

        @Override
        public Tool getToolDefinition() {
            return Tool.builder()
                    .name("weather")
                    .description("weather lookup")
                    .inputSchema(new JsonSchema("object", Map.of("city", Map.of("type", "string")), List.of("city"), false, null, null))
                    .build();
        }

        @Override
        public CallToolResult execute(Map<String, Object> parameters) {
            this.lastArguments = parameters;
            return CallToolResult.builder()
                    .addTextContent("sunny")
                    .build();
        }
    }
}
