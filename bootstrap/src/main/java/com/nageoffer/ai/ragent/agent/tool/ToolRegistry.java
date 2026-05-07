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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class ToolRegistry {

    private final McpToolRegistry mcpToolRegistry;
    private final ConcurrentMap<String, AgentTool> tools = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshFromMcpRegistry();
    }

    public void register(AgentTool tool) {
        if (tool == null || !StringUtils.hasText(tool.name())) {
            return;
        }
        tools.put(tool.name(), tool);
    }

    public Optional<AgentTool> findByName(String name) {
        refreshFromMcpRegistry();
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    public List<AgentTool> listTools() {
        refreshFromMcpRegistry();
        return tools.values().stream()
                .sorted(Comparator.comparing(AgentTool::name))
                .toList();
    }

    public List<String> describeTools() {
        return listTools().stream()
                .map(tool -> tool.name() + ": " + safeDescription(tool))
                .toList();
    }

    public int size() {
        refreshFromMcpRegistry();
        return tools.size();
    }

    private void refreshFromMcpRegistry() {
        List<McpToolExecutor> executors = new ArrayList<>(mcpToolRegistry.listAllExecutors());
        for (McpToolExecutor executor : executors) {
            if (executor != null && executor.getToolDefinition() != null) {
                tools.computeIfAbsent(executor.getToolId(), ignored -> new McpAgentToolAdapter(executor));
            }
        }
    }

    private String safeDescription(AgentTool tool) {
        return tool.description() == null ? "" : tool.description();
    }
}
