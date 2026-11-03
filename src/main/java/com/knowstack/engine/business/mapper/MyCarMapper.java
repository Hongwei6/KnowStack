package com.knowstack.engine.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowstack.engine.business.entity.MyCar;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MyCarMapper extends BaseMapper<MyCar> {
}
