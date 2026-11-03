package com.knowstack.engine.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowstack.engine.business.entity.UserInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {
}
