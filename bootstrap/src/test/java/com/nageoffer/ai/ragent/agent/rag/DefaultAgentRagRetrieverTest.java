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
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultAgentRagRetrieverTest {

    @Test
    void shouldConvertRetrievedChunksToAgentDocuments() {
        StubRetrieverService retrieverService = new StubRetrieverService();
        RAGDefaultProperties defaultProperties = new RAGDefaultProperties();
        defaultProperties.setCollectionName("default-collection");
        SearchChannelProperties searchProperties = new SearchChannelProperties();
        searchProperties.setDefaultTopK(5);
        DefaultAgentRagRetriever retriever = new DefaultAgentRagRetriever(retrieverService, defaultProperties, searchProperties);

        List<AgentDocument> documents = retriever.retrieve("policy", AgentContext.builder()
                .request(AgentRequest.builder()
                        .userInput("policy")
                        .metadata(Map.of("topK", 2))
                        .build())
                .build());

        assertEquals(1, documents.size());
        assertEquals("chunk-1", documents.get(0).getId());
        assertEquals("policy text", documents.get(0).getContent());
        assertEquals("default-collection", documents.get(0).getSource());
        assertEquals(2, retrieverService.lastRequest.getTopK());
        assertEquals("default-collection", retrieverService.lastRequest.getCollectionName());
    }

    private static class StubRetrieverService implements RetrieverService {

        private RetrieveRequest lastRequest;

        @Override
        public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
            this.lastRequest = retrieveParam;
            return List.of(RetrievedChunk.builder()
                    .id("chunk-1")
                    .text("policy text")
                    .score(0.9F)
                    .build());
        }

        @Override
        public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
            return List.of();
        }
    }
}
