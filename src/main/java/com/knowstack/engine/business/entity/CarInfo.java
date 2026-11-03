package com.knowstack.engine.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("car_info")
public class CarInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String infoId;
    private String brand;
    private String modelName;
    private Integer modelYear;
    private String version;
    private String fullName;
    private String vehicleType;
    private String fuelType;
    private Integer seatCount;
    private BigDecimal displacement;
    private BigDecimal motorPower;
    private Integer rangeKm;
    private BigDecimal guidePrice;
    private String colorOptions;
    private String dimensions;
    private Integer wheelbase;
    private String manufacturer;
    private String status;
    private String imageUrl;
    private String description;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
