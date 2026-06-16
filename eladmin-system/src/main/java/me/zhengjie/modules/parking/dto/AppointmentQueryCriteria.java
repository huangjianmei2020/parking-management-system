package me.zhengjie.modules.parking.dto;

import lombok.Data;

@Data
public class AppointmentQueryCriteria {
    private String plateNumber;      // 车牌号（模糊查询）
    private Integer status;          // 状态：0-有效，1-已到场，2-已取消，3-已过期
    private Long lotId;              // 车位ID
    private String startTime;        // 预约开始时间
    private String endTime;          // 预约结束时间
}