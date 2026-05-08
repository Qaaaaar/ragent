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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultMemoryRetrievalService implements MemoryRetrievalService {

    private final MemoryProperties memoryProperties;
    private final PgMemoryVectorSupport vectorSupport;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public String retrieveContext(String userId, String query) {
        if (!Boolean.TRUE.equals(memoryProperties.getLongTermEnabled())
                || !Boolean.TRUE.equals(memoryProperties.getRecallEnabled())
                || StrUtil.isBlank(userId)
                || StrUtil.isBlank(query)) {
            return "";
        }
        try {
            String vectorLiteral = vectorSupport.vectorLiteral(query);
            if (StrUtil.isBlank(vectorLiteral)) {
                return "";
            }
            configureVectorSearch();
            List<MemoryHit> hits = jdbcTemplate.query("""
                            SELECT id,
                                   scope_type,
                                   memory_level,
                                   memory_type,
                                   title,
                                   content,
                                   confidence,
                                   importance,
                                   1 - (embedding <=> ?::vector) AS semantic_score,
                                   ((1 - (embedding <=> ?::vector)) * 0.45)
                                       + ((COALESCE(importance, 50)::float / 100.0) * 0.25)
                                       + (COALESCE(confidence, 0.5) * 0.20)
                                       + (CASE
                                            WHEN last_access_time IS NULL THEN 0
                                            ELSE GREATEST(0, 1 - EXTRACT(EPOCH FROM (NOW() - last_access_time)) / 2592000)
                                          END * 0.10) AS rank_score
                              FROM t_rag_memory
                             WHERE deleted = 0
                               AND status = 'ACTIVE'
                               AND embedding IS NOT NULL
                               AND (expire_time IS NULL OR expire_time > NOW())
                               AND ((scope_type = 'USER' AND owner_user_id = ?) OR scope_type = 'ORG')
                             ORDER BY rank_score DESC
                             LIMIT ?
                            """,
                    (rs, rowNum) -> MemoryHit.builder()
                            .id(rs.getString("id"))
                            .scopeType(rs.getString("scope_type"))
                            .memoryLevel(rs.getString("memory_level"))
                            .memoryType(rs.getString("memory_type"))
                            .title(rs.getString("title"))
                            .content(rs.getString("content"))
                            .confidence(rs.getDouble("confidence"))
                            .importance(rs.getInt("importance"))
                            .semanticScore(rs.getDouble("semantic_score"))
                            .build(),
                    vectorLiteral,
                    vectorLiteral,
                    userId,
                    memoryProperties.getMaxRecall()
            );
            if (CollUtil.isEmpty(hits)) {
                return "";
            }
            refreshAccessStats(hits);
            return formatMemoryContext(hits);
        } catch (Exception e) {
            log.warn("Long-term memory recall failed, userId={}", userId, e);
            return "";
        }
    }

    private void configureVectorSearch() {
        try {
            jdbcTemplate.execute("SET hnsw.ef_search = 200");
        } catch (Exception e) {
            log.debug("Skip pgvector hnsw search tuning for memory recall.", e);
        }
    }

    private void refreshAccessStats(List<MemoryHit> hits) {
        List<String> ids = hits.stream()
                .map(MemoryHit::getId)
                .filter(StrUtil::isNotBlank)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        jdbcTemplate.update(
                "UPDATE t_rag_memory SET last_access_time = NOW(), access_count = COALESCE(access_count, 0) + 1 WHERE id IN (" + placeholders + ")",
                ids.toArray()
        );
    }

    private String formatMemoryContext(List<MemoryHit> hits) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("以下是系统按当前问题召回的相关记忆。用户记忆只用于理解偏好和连续上下文；组织记忆可补充企业背景；若与知识库内容冲突，以知识库内容为准。");
        for (MemoryHit hit : hits) {
            String scope = "ORG".equalsIgnoreCase(hit.getScopeType()) ? "组织记忆" : "用户记忆";
            joiner.add("- [" + scope + "/" + hit.getMemoryLevel() + "/" + hit.getMemoryType() + "] "
                    + hit.getTitle() + ": " + hit.getContent());
        }
        return joiner.toString();
    }

    @lombok.Data
    @lombok.Builder
    private static class MemoryHit {
        private String id;
        private String scopeType;
        private String memoryLevel;
        private String memoryType;
        private String title;
        private String content;
        private Double confidence;
        private Integer importance;
        private Double semanticScore;
    }
}
