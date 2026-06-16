package me.zhengjie.modules.parking.dto;

import lombok.Data;
import java.util.Date;

@Data
public class AppointmentDto {
    private Long id;                    // 预约ID
    private String plateNumber;         // 车牌号
    private Integer vehicleType;        // 车辆类型
    private Long lotId;                 // 预约车位ID
    private String lotCode;             // 预约车位编号
    private Date appointTime;           // 预约时间
    private Date expireTime;            // 过期时间
    private Integer status;             // 状态：0-有效，1-已到场，2-已取消，3-已过期
    private String statusDesc;          // 状态描述
}