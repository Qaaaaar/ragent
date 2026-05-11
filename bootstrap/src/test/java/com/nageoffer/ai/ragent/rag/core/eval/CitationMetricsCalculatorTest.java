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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationMetricsCalculatorTest {

    private final CitationMetricsCalculator calculator = new CitationMetricsCalculator(new CitationExtractor());

    @Test
    void shouldCalculateCitationPrecisionRecallAndAccuracy() {
        EvalCase evalCase = EvalCase.builder()
                .requiredCitationChunkIds(List.of("chunk_13"))
                .build();
        List<EvalChunk> retrieved = List.of(chunk("chunk_13"));
        JudgeResult judgeResult = JudgeResult.builder()
                .citationSupport(List.of(CitationSupportResult.builder()
                        .citationChunkId("chunk_13")
                        .score(5)
                        .supported(true)
                        .build()))
                .build();

        CitationMetricResult result = calculator.evaluate(
                evalCase,
                retrieved,
                "质量问题退货运费由卖家承担。[chunk_13]",
                judgeResult
        );

        assertFalse(result.isSkipped());
        assertEquals(1D, result.getPrecision());
        assertEquals(1D, result.getRecall());
        assertTrue(result.isFullyCorrect());
    }

    @Test
    void shouldFailCitationWhenCitationWasNotRetrieved() {
        EvalCase evalCase = EvalCase.builder()
                .requiredCitationChunkIds(List.of("chunk_13"))
                .build();

        CitationMetricResult result = calculator.evaluate(
                evalCase,
                List.of(chunk("chunk_12")),
                "质量问题退货运费由卖家承担。[chunk_13]",
                JudgeResult.builder().build()
        );

        assertEquals(0D, result.getPrecision());
        assertEquals(0D, result.getRecall());
        assertFalse(result.isFullyCorrect());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("not retrieved")));
    }

    @Test
    void shouldFailCitationWhenAnswerHasNoStructuredCitation() {
        EvalCase evalCase = EvalCase.builder()
                .requiredCitationChunkIds(List.of("chunk_13"))
                .build();

        CitationMetricResult result = calculator.evaluate(
                evalCase,
                List.of(chunk("chunk_13")),
                "质量问题退货运费由卖家承担。",
                JudgeResult.builder().build()
        );

        assertEquals(0, result.getCitationCount());
        assertEquals(0D, result.getPrecision());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("no structured citation")));
    }

    private EvalChunk chunk(String id) {
        return EvalChunk.builder().chunkId(id).content(id + " content").build();
    }
}
