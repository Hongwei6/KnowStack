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
@TableName("car_order")
public class CarOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderId;
    private String userId;
    private String carId;
    private String orderNo;
    private String orderType;
    private String orderStatus;
    private String brand;
    private String model;
    private String color;
    private String vin;
    private String sellerName;
    private String sellerContact;
    private BigDecimal vehiclePrice;
    private BigDecimal purchaseTax;
    private BigDecimal insuranceFee;
    private BigDecimal otherFee;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal actualAmount;
    private String paymentMethod;
    private LocalDate orderDate;
    private LocalDate deliveryDate;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
