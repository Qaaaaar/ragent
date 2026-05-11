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

package com.nageoffer.ai.ragent.rag.core.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.util.StringUtils;

import java.util.Map;

final class McpToolResultSupport {

    private McpToolResultSupport() {
    }

    static CallToolResult json(ObjectMapper objectMapper, Object payload) {
        try {
            return CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (JsonProcessingException ex) {
            return error("tool result serialization failed: " + ex.getMessage());
        }
    }

    static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }

    static CallToolResult errorJson(ObjectMapper objectMapper, Object payload, String fallback) {
        try {
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (JsonProcessingException ex) {
            return error(fallback);
        }
    }

    static String stringParam(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    static int intParam(Map<String, Object> parameters, int defaultValue, int min, int max, String... keys) {
        int value = defaultValue;
        if (parameters != null && keys != null) {
            for (String key : keys) {
                Object raw = parameters.get(key);
                if (raw instanceof Number number) {
                    value = number.intValue();
                    break;
                }
                if (raw instanceof String text && StringUtils.hasText(text)) {
                    try {
                        value = Integer.parseInt(text.trim());
                        break;
                    } catch (NumberFormatException ignored) {
                        value = defaultValue;
                    }
                }
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    static boolean booleanParam(Map<String, Object> parameters, boolean defaultValue, String... keys) {
        if (parameters == null || keys == null) {
            return defaultValue;
        }
        for (String key : keys) {
            Object raw = parameters.get(key);
            if (raw instanceof Boolean bool) {
                return bool;
            }
            if (raw instanceof String text && StringUtils.hasText(text)) {
                return Boolean.parseBoolean(text.trim());
            }
        }
        return defaultValue;
    }

    static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (limit <= 0 || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    static String snippetAround(String content, String needle, int limit) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        if (!StringUtils.hasText(needle)) {
            return truncate(content, limit);
        }
        String lowerContent = content.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        int hit = lowerContent.indexOf(lowerNeedle);
        if (hit < 0) {
            return truncate(content, limit);
        }
        int half = Math.max(20, limit / 2);
        int start = Math.max(0, hit - half);
        int end = Math.min(content.length(), hit + needle.length() + half);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < content.length() ? "..." : "";
        return prefix + content.substring(start, end) + suffix;
    }
}
