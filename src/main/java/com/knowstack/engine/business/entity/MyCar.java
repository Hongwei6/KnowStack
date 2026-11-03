package com.knowstack.engine.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("my_car")
public class MyCar {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String carId;
    private String userId;
    private String carInfoId;
    private String nickname;
    private String fullName;
    private String imageUrl;
    private String orderId;
    private String plateNumber;
    private String color;
    private String vin;
    private String engineNumber;
    private LocalDate purchaseDate;
    private BigDecimal purchasePrice;
    private Integer mileage;
    private LocalDate registerDate;
    private LocalDate insuranceExpireDate;
    private LocalDate inspectionExpireDate;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
