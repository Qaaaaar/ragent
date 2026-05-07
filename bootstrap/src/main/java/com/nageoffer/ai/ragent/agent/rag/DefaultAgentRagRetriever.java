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

package com.nageoffer.ai.ragent.agent.rag;

import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultAgentRagRetriever implements AgentRagRetriever {

    private final RetrieverService retrieverService;
    private final RAGDefaultProperties defaultProperties;
    private final SearchChannelProperties searchProperties;

    @Override
    public List<AgentDocument> retrieve(String query, AgentContext context) {
        Map<String, Object> metadata = context != null
                && context.getRequest() != null
                && context.getRequest().getMetadata() != null
                ? context.getRequest().getMetadata()
                : Map.of();
        int topK = resolveTopK(metadata);
        String collectionName = resolveCollectionName(metadata);
        RetrieveRequest request = RetrieveRequest.builder()
                .query(query)
                .topK(topK)
                .collectionName(collectionName)
                .metadataFilters(extractFilters(metadata))
                .build();
        return retrieverService.retrieve(request).stream()
                .map(chunk -> toAgentDocument(chunk, collectionName))
                .toList();
    }

    private int resolveTopK(Map<String, Object> metadata) {
        Object topK = metadata.get("topK");
        if (topK instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (topK instanceof String text && StringUtils.hasText(text)) {
            try {
                return Math.max(1, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return searchProperties.getDefaultTopK();
            }
        }
        return searchProperties.getDefaultTopK();
    }

    private String resolveCollectionName(Map<String, Object> metadata) {
        Object collectionName = metadata.get("collectionName");
        if (collectionName instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        return defaultProperties.getCollectionName();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFilters(Map<String, Object> metadata) {
        Object filters = metadata.get("metadataFilters");
        if (filters instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    result.put(key.toString(), value);
                }
            });
            return result;
        }
        return Map.of();
    }

    private AgentDocument toAgentDocument(RetrievedChunk chunk, String collectionName) {
        Map<String, Object> metadata = new HashMap<>();
        if (StringUtils.hasText(collectionName)) {
            metadata.put("collectionName", collectionName);
        }
        return AgentDocument.builder()
                .id(chunk.getId())
                .content(chunk.getText())
                .source(collectionName)
                .score(chunk.getScore() == null ? 0D : chunk.getScore())
                .metadata(metadata)
                .build();
    }
}
