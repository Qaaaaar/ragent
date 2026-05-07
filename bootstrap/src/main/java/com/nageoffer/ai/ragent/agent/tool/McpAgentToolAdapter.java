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
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class McpAgentToolAdapter implements AgentTool {

    private final McpToolExecutor executor;

    @Override
    public String name() {
        return executor.getToolDefinition().name();
    }

    @Override
    public String description() {
        return executor.getToolDefinition().description();
    }

    @Override
    public Map<String, Object> schema() {
        Tool tool = executor.getToolDefinition();
        JsonSchema inputSchema = tool.inputSchema();
        if (inputSchema == null) {
            return Map.of();
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        if (inputSchema.type() != null) {
            schema.put("type", inputSchema.type());
        }
        if (inputSchema.properties() != null) {
            schema.put("properties", inputSchema.properties());
        }
        if (inputSchema.required() != null) {
            schema.put("required", inputSchema.required());
        }
        if (inputSchema.additionalProperties() != null) {
            schema.put("additionalProperties", inputSchema.additionalProperties());
        }
        return schema;
    }

    @Override
    public ToolCallResult call(Map<String, Object> arguments) {
        long startMs = System.currentTimeMillis();
        Map<String, Object> safeArguments = arguments != null ? arguments : Map.of();
        try {
            CallToolResult result = executor.execute(safeArguments);
            boolean success = result != null && !Boolean.TRUE.equals(result.isError());
            String raw = stringify(result);
            return ToolCallResult.builder()
                    .toolName(name())
                    .arguments(safeArguments)
                    .rawResult(raw)
                    .success(success)
                    .errorMessage(success ? null : raw)
                    .latencyMs(System.currentTimeMillis() - startMs)
                    .build();
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ToolCallResult.builder()
                    .toolName(name())
                    .arguments(safeArguments)
                    .rawResult("")
                    .success(false)
                    .errorMessage(reason)
                    .latencyMs(System.currentTimeMillis() - startMs)
                    .build();
        }
    }

    private String stringify(CallToolResult result) {
        if (result == null) {
            return "";
        }
        String content = stringifyContent(result.content());
        if (result.structuredContent() != null) {
            String structured = Objects.toString(result.structuredContent(), "");
            return content.isBlank() ? structured : content + "\n" + structured;
        }
        return content;
    }

    private String stringifyContent(List<Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
                .map(item -> item instanceof TextContent textContent
                        ? textContent.text()
                        : Objects.toString(item, ""))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
