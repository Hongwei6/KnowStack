package com.knowstack.engine.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
