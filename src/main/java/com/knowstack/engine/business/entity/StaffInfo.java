package com.knowstack.engine.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("staff_info")
public class StaffInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String empId;
    private String name;
    private String password;
    private String job;
    private LocalDate entryTime;
    private LocalDate birthday;
    private String educationalBackground;
    private Long directorId;
    private Long deptId;
    private String duty;
    private String motto;
    private String picUrl;
    private String status;
    private LocalDate resignationTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
