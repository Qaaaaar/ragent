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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.controller.vo.RagMemoryVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagMemoryDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class RagMemoryServiceSupport {

    private final ObjectMapper objectMapper;

    RagMemoryVO toVO(RagMemoryDO record) {
        if (record == null) {
            return null;
        }
        return RagMemoryVO.builder()
                .id(record.getId())
                .scopeType(record.getScopeType())
                .ownerUserId(record.getOwnerUserId())
                .sourceUserId(record.getSourceUserId())
                .memoryLevel(record.getMemoryLevel())
                .memoryType(record.getMemoryType())
                .title(record.getTitle())
                .content(record.getContent())
                .keywords(readList(record.getKeywords()))
                .sourceConversationId(record.getSourceConversationId())
                .sourceMessageIds(readList(record.getSourceMessageIds()))
                .confidence(record.getConfidence())
                .importance(record.getImportance())
                .status(record.getStatus())
                .expireTime(record.getExpireTime())
                .lastAccessTime(record.getLastAccessTime())
                .accessCount(record.getAccessCount())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }

    List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
