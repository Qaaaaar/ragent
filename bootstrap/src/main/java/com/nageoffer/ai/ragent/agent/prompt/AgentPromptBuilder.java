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

package com.nageoffer.ai.ragent.agent.prompt;

import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.memory.MemoryMessage;
import com.nageoffer.ai.ragent.agent.rag.AgentDocument;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentPromptBuilder {

    private static final int MEMORY_LIMIT = 500;
    private static final int DOCUMENT_LIMIT = 1600;
    private static final int TOOL_RESULT_LIMIT = 1200;

    public String buildPlannerPrompt(AgentContext context, List<AgentTool> tools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an Agent planner. Decide what the Agent should do next.\n");
        prompt.append("Return only a JSON object. Do not answer the user.\n\n");
        prompt.append("JSON schema:\n");
        prompt.append("{\n");
        prompt.append("  \"needRag\": true|false,\n");
        prompt.append("  \"needTool\": true|false,\n");
        prompt.append("  \"toolCalls\": [{\"toolName\":\"name\", \"arguments\": {}}],\n");
        prompt.append("  \"shouldAnswerDirectly\": true|false,\n");
        prompt.append("  \"reasoningSummary\": \"short safe summary\",\n");
        prompt.append("  \"finish\": true|false,\n");
        prompt.append("  \"maxIterations\": 3\n");
        prompt.append("}\n\n");
        prompt.append("Rules:\n");
        prompt.append("- Use RAG for knowledge base, documents, internal material, citations, policies, or project-specific facts.\n");
        prompt.append("- Use tools for external systems, live status, files, APIs, tickets, weather, sales, database or MCP capabilities.\n");
        prompt.append("- Use both when the answer needs document context and tool output.\n");
        prompt.append("- shouldAnswerDirectly is true only for simple chat or general explanation.\n");
        prompt.append("- reasoningSummary must be brief and must not reveal hidden reasoning.\n\n");
        prompt.append("User input:\n").append(context.getRequest().getUserInput()).append("\n\n");
        prompt.append("Recent memory:\n").append(formatMemory(context.getMemoryMessages())).append("\n\n");
        prompt.append("Already retrieved documents: ").append(context.getRetrievedContext().size()).append("\n");
        prompt.append("Already called tools: ").append(context.getToolResults().size()).append("\n\n");
        prompt.append("Available tools:\n").append(formatTools(tools));
        return prompt.toString();
    }

    public String buildToolSelectionPrompt(String userInput, List<AgentTool> tools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Select MCP tools for the user request and return JSON only.\n");
        prompt.append("User input:\n").append(userInput).append("\n\n");
        prompt.append("Available tools:\n").append(formatTools(tools));
        return prompt.toString();
    }

    public String buildFinalAnswerPrompt(AgentContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the final answer generator for a Java Agentic RAG system.\n");
        prompt.append("Answer directly in the user's language.\n\n");
        prompt.append("Requirements:\n");
        prompt.append("- Use the user question, memory, RAG context, and MCP tool results below.\n");
        prompt.append("- If RAG context is insufficient, say the available evidence is insufficient.\n");
        prompt.append("- Do not invent tool results.\n");
        prompt.append("- If a tool call failed, mention the failure reason and provide the best alternative answer possible.\n");
        prompt.append("- Keep the answer focused on the user's question.\n\n");
        prompt.append("User question:\n").append(context.getRequest().getUserInput()).append("\n\n");
        prompt.append("Memory summary:\n").append(formatMemory(context.getMemoryMessages())).append("\n\n");
        prompt.append("RAG context:\n").append(formatDocuments(context.getRetrievedContext())).append("\n\n");
        prompt.append("MCP tool results:\n").append(formatToolResults(context.getToolResults())).append("\n");
        return prompt.toString();
    }

    private String formatMemory(List<MemoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (MemoryMessage message : messages) {
            builder.append("- ")
                    .append(message.getRole())
                    .append(": ")
                    .append(truncate(message.getContent(), MEMORY_LIMIT))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String formatDocuments(List<AgentDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            AgentDocument document = documents.get(i);
            builder.append("[doc-").append(i + 1).append("]\n");
            builder.append("id: ").append(document.getId()).append("\n");
            builder.append("source: ").append(document.getSource()).append("\n");
            builder.append("score: ").append(document.getScore()).append("\n");
            builder.append("content: ").append(truncate(document.getContent(), DOCUMENT_LIMIT)).append("\n\n");
        }
        return builder.toString().trim();
    }

    private String formatToolResults(List<ToolCallResult> results) {
        if (results == null || results.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolCallResult result : results) {
            builder.append("- tool: ").append(result.getToolName()).append("\n");
            builder.append("  success: ").append(result.isSuccess()).append("\n");
            builder.append("  arguments: ").append(result.getArguments()).append("\n");
            if (result.isSuccess()) {
                builder.append("  result: ").append(truncate(result.getRawResult(), TOOL_RESULT_LIMIT)).append("\n");
            } else {
                builder.append("  error: ").append(truncate(result.getErrorMessage(), TOOL_RESULT_LIMIT)).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String formatTools(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentTool tool : tools) {
            builder.append("- name: ").append(tool.name()).append("\n");
            builder.append("  description: ").append(tool.description()).append("\n");
            Map<String, Object> schema = tool.schema();
            builder.append("  schema: ").append(schema == null ? "{}" : schema).append("\n");
        }
        return builder.toString().trim();
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit)) + "...";
    }
}
