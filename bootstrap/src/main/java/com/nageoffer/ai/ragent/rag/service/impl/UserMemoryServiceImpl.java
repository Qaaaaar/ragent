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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagMemoryVO;
import com.nageoffer.ai.ragent.rag.core.memory.RagMemoryCommand;
import com.nageoffer.ai.ragent.rag.core.memory.RagMemoryPersistenceService;
import com.nageoffer.ai.ragent.rag.dao.entity.RagMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagMemoryMapper;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryLevel;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryScopeType;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryStatus;
import com.nageoffer.ai.ragent.rag.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserMemoryServiceImpl implements UserMemoryService {

    private final RagMemoryMapper ragMemoryMapper;
    private final RagMemoryPersistenceService memoryPersistenceService;
    private final RagMemoryServiceSupport support;
    private final ObjectMapper objectMapper;

    @Override
    public IPage<RagMemoryVO> pageQuery(RagMemoryPageRequest requestParam) {
        String userId = UserContext.getUserId();
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<RagMemoryDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<RagMemoryDO> result = ragMemoryMapper.selectPage(
                page,
                Wrappers.lambdaQuery(RagMemoryDO.class)
                        .eq(RagMemoryDO::getDeleted, 0)
                        .eq(RagMemoryDO::getScopeType, RagMemoryScopeType.USER.name())
                        .eq(RagMemoryDO::getOwnerUserId, userId)
                        .eq(StrUtil.isNotBlank(requestParam.getMemoryLevel()), RagMemoryDO::getMemoryLevel,
                                requestParam.getMemoryLevel())
                        .eq(StrUtil.isNotBlank(requestParam.getMemoryType()), RagMemoryDO::getMemoryType,
                                requestParam.getMemoryType())
                        .eq(StrUtil.isNotBlank(requestParam.getStatus()), RagMemoryDO::getStatus,
                                requestParam.getStatus())
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(RagMemoryDO::getTitle, keyword)
                                .or()
                                .like(RagMemoryDO::getContent, keyword)
                                .or()
                                .like(RagMemoryDO::getMemoryType, keyword))
                        .orderByDesc(RagMemoryDO::getUpdateTime)
        );
        return result.convert(support::toVO);
    }

    @Override
    public String create(RagMemoryCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("Memory request cannot be empty"));
        String userId = UserContext.getUserId();
        RagMemoryDO record = memoryPersistenceService.create(RagMemoryCommand.builder()
                .scopeType(RagMemoryScopeType.USER.name())
                .ownerUserId(userId)
                .sourceUserId(userId)
                .memoryLevel(RagMemoryLevel.from(requestParam.getMemoryLevel(), RagMemoryLevel.LONG_TERM).name())
                .memoryType(requestParam.getMemoryType())
                .title(requestParam.getTitle())
                .content(requestParam.getContent())
                .keywords(requestParam.getKeywords())
                .confidence(requestParam.getConfidence() == null ? 1D : requestParam.getConfidence())
                .importance(requestParam.getImportance())
                .status(RagMemoryStatus.from(requestParam.getStatus(), RagMemoryStatus.ACTIVE).name())
                .expireTime(requestParam.getExpireTime())
                .operatorUserId(userId)
                .build());
        return record.getId();
    }

    @Override
    public void update(String id, RagMemoryUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("Memory request cannot be empty"));
        RagMemoryDO record = loadOwnMemory(id);
        if (requestParam.getMemoryLevel() != null) {
            record.setMemoryLevel(RagMemoryLevel.from(requestParam.getMemoryLevel(), RagMemoryLevel.LONG_TERM).name());
        }
        if (requestParam.getMemoryType() != null) {
            record.setMemoryType(StrUtil.trimToNull(requestParam.getMemoryType()));
        }
        if (requestParam.getTitle() != null) {
            record.setTitle(StrUtil.trimToNull(requestParam.getTitle()));
        }
        if (requestParam.getContent() != null) {
            record.setContent(StrUtil.trimToNull(requestParam.getContent()));
        }
        if (requestParam.getKeywords() != null) {
            record.setKeywords(writeKeywords(requestParam.getKeywords()));
        }
        if (requestParam.getConfidence() != null) {
            record.setConfidence(Math.max(0D, Math.min(1D, requestParam.getConfidence())));
        }
        if (requestParam.getImportance() != null) {
            record.setImportance(Math.max(0, Math.min(100, requestParam.getImportance())));
        }
        if (requestParam.getStatus() != null) {
            record.setStatus(RagMemoryStatus.from(requestParam.getStatus(), RagMemoryStatus.ACTIVE).name());
        }
        if (requestParam.getExpireTime() != null) {
            record.setExpireTime(requestParam.getExpireTime());
        }
        Assert.notBlank(record.getContent(), () -> new ClientException("Memory content cannot be empty"));
        record.setUpdateBy(UserContext.getUserId());
        ragMemoryMapper.updateById(record);
        memoryPersistenceService.refreshEmbedding(record);
    }

    @Override
    public void delete(String id) {
        ragMemoryMapper.deleteById(loadOwnMemory(id).getId());
    }

    private RagMemoryDO loadOwnMemory(String id) {
        RagMemoryDO record = ragMemoryMapper.selectOne(
                Wrappers.lambdaQuery(RagMemoryDO.class)
                        .eq(RagMemoryDO::getId, id)
                        .eq(RagMemoryDO::getScopeType, RagMemoryScopeType.USER.name())
                        .eq(RagMemoryDO::getOwnerUserId, UserContext.getUserId())
                        .eq(RagMemoryDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("Memory does not exist"));
        return record;
    }

    private String writeKeywords(List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords == null ? List.of() : keywords);
        } catch (Exception e) {
            throw new ClientException("Memory JSON serialization failed");
        }
    }
}
