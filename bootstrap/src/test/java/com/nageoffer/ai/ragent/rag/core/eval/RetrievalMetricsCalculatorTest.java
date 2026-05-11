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

class RetrievalMetricsCalculatorTest {

    private final RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();

    @Test
    void shouldCalculateRecallHitAndMrrForMultiChunkAnswer() {
        EvalCase evalCase = EvalCase.builder()
                .relevantChunkIds(List.of("chunk_12", "chunk_13", "chunk_14"))
                .build();
        List<EvalChunk> retrieved = List.of(
                chunk("chunk_12"),
                chunk("chunk_05"),
                chunk("chunk_13"),
                chunk("chunk_33"),
                chunk("chunk_67")
        );

        RetrievalMetricResult result = calculator.evaluate(evalCase, retrieved);

        assertFalse(result.isSkipped());
        assertTrue(result.isHit());
        assertEquals(1.0D, result.getReciprocalRank());
        assertEquals(2.0D / 3.0D, result.getRecallAtK());
        assertEquals(2, result.getMatchedRelevantCount());
    }

    @Test
    void shouldSkipRetrievalMetricsForNoAnswerSample() {
        EvalCase evalCase = EvalCase.builder()
                .noAnswerExpected(true)
                .relevantChunkIds(List.of())
                .build();

        RetrievalMetricResult result = calculator.evaluate(evalCase, List.of(chunk("chunk_01")));

        assertTrue(result.isSkipped());
        assertFalse(result.isHit());
        assertEquals(0D, result.getRecallAtK());
    }

    private EvalChunk chunk(String id) {
        return EvalChunk.builder().chunkId(id).content(id + " content").build();
    }
}
