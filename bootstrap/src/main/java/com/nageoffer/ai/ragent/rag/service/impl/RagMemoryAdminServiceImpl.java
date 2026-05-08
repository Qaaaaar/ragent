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
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryReviewRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryStatusRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagMemoryVO;
import com.nageoffer.ai.ragent.rag.core.memory.RagMemoryCommand;
import com.nageoffer.ai.ragent.rag.core.memory.RagMemoryPersistenceService;
import com.nageoffer.ai.ragent.rag.dao.entity.RagMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagMemoryMapper;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryLevel;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryScopeType;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryStatus;
import com.nageoffer.ai.ragent.rag.service.RagMemoryAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RagMemoryAdminServiceImpl implements RagMemoryAdminService {

    private final RagMemoryMapper ragMemoryMapper;
    private final RagMemoryPersistenceService memoryPersistenceService;
    private final RagMemoryServiceSupport support;
    private final ObjectMapper objectMapper;

    @Override
    public IPage<RagMemoryVO> pageQuery(RagMemoryPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<RagMemoryDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<RagMemoryDO> result = ragMemoryMapper.selectPage(
                page,
                Wrappers.lambdaQuery(RagMemoryDO.class)
                        .eq(RagMemoryDO::getDeleted, 0)
                        .eq(StrUtil.isNotBlank(requestParam.getScopeType()), RagMemoryDO::getScopeType,
                                requestParam.getScopeType())
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
        RagMemoryDO record = memoryPersistenceService.create(toCommand(requestParam));
        return record.getId();
    }

    @Override
    public void update(String id, RagMemoryUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("Memory request cannot be empty"));
        RagMemoryDO record = loadById(id);
        applyUpdate(record, requestParam);
        record.setUpdateBy(UserContext.getUserId());
        ragMemoryMapper.updateById(record);
        memoryPersistenceService.refreshEmbedding(record);
    }

    @Override
    public void delete(String id) {
        ragMemoryMapper.deleteById(loadById(id).getId());
    }

    @Override
    public void updateStatus(String id, RagMemoryStatusRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("Memory status request cannot be empty"));
        RagMemoryDO record = loadById(id);
        record.setStatus(RagMemoryStatus.from(requestParam.getStatus(), RagMemoryStatus.ARCHIVED).name());
        record.setUpdateBy(UserContext.getUserId());
        ragMemoryMapper.updateById(record);
    }

    @Override
    public void review(String id, RagMemoryReviewRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("Memory review request cannot be empty"));
        RagMemoryDO record = loadById(id);
        record.setStatus(Boolean.TRUE.equals(requestParam.getApproved())
                ? RagMemoryStatus.ACTIVE.name()
                : RagMemoryStatus.REJECTED.name());
        record.setUpdateBy(UserContext.getUserId());
        ragMemoryMapper.updateById(record);
    }

    private RagMemoryCommand toCommand(RagMemoryCreateRequest requestParam) {
        RagMemoryScopeType scope = RagMemoryScopeType.from(requestParam.getScopeType(), RagMemoryScopeType.USER);
        if (scope == RagMemoryScopeType.USER && StrUtil.isBlank(requestParam.getOwnerUserId())) {
            throw new ClientException("User memory owner cannot be empty");
        }
        return RagMemoryCommand.builder()
                .scopeType(scope.name())
                .ownerUserId(requestParam.getOwnerUserId())
                .sourceUserId(UserContext.getUserId())
                .memoryLevel(RagMemoryLevel.from(requestParam.getMemoryLevel(), RagMemoryLevel.LONG_TERM).name())
                .memoryType(requestParam.getMemoryType())
                .title(requestParam.getTitle())
                .content(requestParam.getContent())
                .keywords(requestParam.getKeywords())
                .confidence(requestParam.getConfidence())
                .importance(requestParam.getImportance())
                .status(RagMemoryStatus.from(requestParam.getStatus(), RagMemoryStatus.ACTIVE).name())
                .expireTime(requestParam.getExpireTime())
                .operatorUserId(UserContext.getUserId())
                .build();
    }

    private void applyUpdate(RagMemoryDO record, RagMemoryUpdateRequest requestParam) {
        if (requestParam.getScopeType() != null) {
            RagMemoryScopeType scope = RagMemoryScopeType.from(requestParam.getScopeType(), RagMemoryScopeType.USER);
            record.setScopeType(scope.name());
            record.setOwnerUserId(scope == RagMemoryScopeType.USER ? StrUtil.trimToNull(requestParam.getOwnerUserId()) : null);
        }
        if (requestParam.getOwnerUserId() != null && RagMemoryScopeType.USER.name().equals(record.getScopeType())) {
            record.setOwnerUserId(StrUtil.trimToNull(requestParam.getOwnerUserId()));
        }
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
        if (RagMemoryScopeType.USER.name().equals(record.getScopeType()) && StrUtil.isBlank(record.getOwnerUserId())) {
            throw new ClientException("User memory owner cannot be empty");
        }
        Assert.notBlank(record.getContent(), () -> new ClientException("Memory content cannot be empty"));
    }

    private String writeKeywords(java.util.List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords == null ? java.util.List.of() : keywords);
        } catch (Exception e) {
            throw new ClientException("Memory JSON serialization failed");
        }
    }

    private RagMemoryDO loadById(String id) {
        RagMemoryDO record = ragMemoryMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("Memory does not exist"));
        return record;
    }
}
