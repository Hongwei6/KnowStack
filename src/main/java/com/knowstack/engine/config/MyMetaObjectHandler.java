package com.knowstack.engine.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.knowstack.engine.document.entity.BaseEntity;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * BaseEntity 的 created_at / updated_at 自动填充
 * （字段带 FieldFill 注解但没有 Handler 时，MyBatis-Plus 会插入 NULL 导致列非空约束失败）
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
