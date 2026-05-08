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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagMemoryMapper;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryLevel;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryScopeType;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DefaultRagMemoryPersistenceService implements RagMemoryPersistenceService {

    private final RagMemoryMapper ragMemoryMapper;
    private final PgMemoryVectorSupport vectorSupport;
    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;

    @Override
    public RagMemoryDO create(RagMemoryCommand command) {
        if (command == null || StrUtil.isBlank(command.getContent())) {
            throw new ClientException("Memory content cannot be empty");
        }

        RagMemoryScopeType scope = RagMemoryScopeType.from(command.getScopeType(), RagMemoryScopeType.USER);
        RagMemoryLevel level = RagMemoryLevel.from(command.getMemoryLevel(), RagMemoryLevel.LONG_TERM);
        RagMemoryStatus status = RagMemoryStatus.from(command.getStatus(), RagMemoryStatus.ACTIVE);
        String operator = StrUtil.blankToDefault(command.getOperatorUserId(), command.getSourceUserId());
        String ownerUserId = scope == RagMemoryScopeType.USER
                ? StrUtil.blankToDefault(command.getOwnerUserId(), operator)
                : null;
        if (scope == RagMemoryScopeType.USER && StrUtil.isBlank(ownerUserId)) {
            throw new ClientException("User memory owner cannot be empty");
        }

        RagMemoryDO memory = RagMemoryDO.builder()
                .scopeType(scope.name())
                .ownerUserId(ownerUserId)
                .sourceUserId(StrUtil.blankToDefault(command.getSourceUserId(), operator))
                .memoryLevel(level.name())
                .memoryType(StrUtil.blankToDefault(command.getMemoryType(), "fact"))
                .title(StrUtil.blankToDefault(command.getTitle(), "Memory"))
                .content(command.getContent().trim())
                .keywords(writeJson(command.getKeywords()))
                .sourceConversationId(StrUtil.trimToNull(command.getSourceConversationId()))
                .sourceMessageIds(writeJson(command.getSourceMessageIds()))
                .confidence(command.getConfidence() == null ? 1D : clamp(command.getConfidence()))
                .importance(command.getImportance() == null ? 50 : clampImportance(command.getImportance()))
                .status(status.name())
                .expireTime(resolveExpireTime(level, command.getExpireTime()))
                .accessCount(0)
                .createBy(operator)
                .updateBy(operator)
                .build();
        ragMemoryMapper.insert(memory);
        refreshEmbedding(memory);
        return memory;
    }

    @Override
    public void refreshEmbedding(RagMemoryDO memory) {
        if (memory == null || StrUtil.isBlank(memory.getId())) {
            return;
        }
        vectorSupport.updateEmbedding(memory.getId(), buildIndexText(memory));
    }

    @Override
    public boolean existsActiveLike(RagMemoryCommand command) {
        if (command == null || StrUtil.isBlank(command.getContent())) {
            return false;
        }
        RagMemoryScopeType scope = RagMemoryScopeType.from(command.getScopeType(), RagMemoryScopeType.USER);
        String ownerUserId = scope == RagMemoryScopeType.USER
                ? StrUtil.blankToDefault(command.getOwnerUserId(), command.getSourceUserId())
                : null;
        Long count = ragMemoryMapper.selectCount(
                Wrappers.lambdaQuery(RagMemoryDO.class)
                        .eq(RagMemoryDO::getDeleted, 0)
                        .eq(RagMemoryDO::getScopeType, scope.name())
                        .eq(scope == RagMemoryScopeType.USER, RagMemoryDO::getOwnerUserId, ownerUserId)
                        .eq(RagMemoryDO::getContent, command.getContent().trim())
                        .in(RagMemoryDO::getStatus,
                                RagMemoryStatus.ACTIVE.name(),
                                RagMemoryStatus.PENDING_REVIEW.name())
        );
        return count != null && count > 0;
    }

    private Date resolveExpireTime(RagMemoryLevel level, Date requested) {
        if (requested != null) {
            return requested;
        }
        if (level != RagMemoryLevel.WORKING) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, memoryProperties.getWorkingTtlDays());
        return calendar.getTime();
    }

    private String buildIndexText(RagMemoryDO memory) {
        return String.join("\n",
                StrUtil.nullToEmpty(memory.getMemoryType()),
                StrUtil.nullToEmpty(memory.getTitle()),
                StrUtil.nullToEmpty(memory.getContent()),
                StrUtil.nullToEmpty(memory.getKeywords())
        );
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new ClientException("Memory JSON serialization failed");
        }
    }

    private Double clamp(Double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private Integer clampImportance(Integer value) {
        return Math.max(0, Math.min(100, value));
    }
}
