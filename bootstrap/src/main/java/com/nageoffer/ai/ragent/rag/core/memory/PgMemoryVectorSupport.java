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

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgMemoryVectorSupport {

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    public String vectorLiteral(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        List<Float> embedding = embeddingService.embed(text);
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        return toVectorLiteral(normalize(toArray(embedding)));
    }

    public void updateEmbedding(String memoryId, String indexText) {
        if (StrUtil.isBlank(memoryId) || StrUtil.isBlank(indexText)) {
            return;
        }
        try {
            String vectorLiteral = vectorLiteral(indexText);
            if (StrUtil.isBlank(vectorLiteral)) {
                return;
            }
            jdbcTemplate.update(
                    "UPDATE t_rag_memory SET embedding = ?::vector WHERE id = ?",
                    vectorLiteral,
                    memoryId
            );
        } catch (Exception e) {
            log.warn("Memory embedding update failed, memoryId={}", memoryId, e);
        }
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
