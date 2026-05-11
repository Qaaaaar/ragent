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

package com.nageoffer.ai.ragent.rag.core.eval;

import com.nageoffer.ai.ragent.rag.config.RagEvaluationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RagEvaluationServiceTest {

    @Test
    void shouldAggregateLayeredEvaluationMetrics() {
        RagEvaluationService service = new RagEvaluationService(
                new RetrievalMetricsCalculator(),
                new CitationMetricsCalculator(new CitationExtractor()),
                sample -> JudgeResult.builder()
                        .faithfulness(score(5))
                        .answerRelevancy(score(5))
                        .answerCorrectness(score(5))
                        .citationSupport(List.of(CitationSupportResult.builder()
                                .citationChunkId("chunk_13")
                                .score(5)
                                .supported(true)
                                .build()))
                        .overallPass(true)
                        .build(),
                new RagEvaluationProperties()
        );
        EvalSample sample = EvalSample.builder()
                .evalCase(EvalCase.builder()
                        .query("退货运费谁承担？")
                        .expectedAnswer("质量问题由卖家承担。")
                        .relevantChunkIds(List.of("chunk_13"))
                        .requiredCitationChunkIds(List.of("chunk_13"))
                        .build())
                .retrievedChunks(List.of(EvalChunk.builder().chunkId("chunk_13").content("质量问题由卖家承担").build()))
                .actualAnswer("质量问题由卖家承担。[chunk_13]")
                .build();

        EvalReport report = service.evaluate(List.of(sample));
        Map<String, Double> metrics = report.getMetrics();

        assertEquals(1D, metrics.get("hitRate"));
        assertEquals(1D, metrics.get("mrr"));
        assertEquals(1D, metrics.get("recallAtK"));
        assertEquals(5D, metrics.get("averageFaithfulness"));
        assertEquals(1D, metrics.get("citationAccuracy"));
        assertFalse(report.getFailedThresholds().contains("hitRate"));
    }

    private ScoreResult score(int score) {
        return ScoreResult.builder().score(score).reason("ok").build();
    }
}
