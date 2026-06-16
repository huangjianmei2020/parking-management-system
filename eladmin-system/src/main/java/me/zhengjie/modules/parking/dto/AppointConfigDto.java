package me.zhengjie.modules.parking.dto;

import lombok.Data;

@Data
public class AppointConfigDto {
    private Integer reserveMinutes = 30;       // 预约保留时长（分钟）
    private Integer maxAppointPerDay = 50;     // 单日最大预约数
    private Integer cancelLimitMinutes = 10;    // 取消预约时限（分钟）
    private Integer todayAppointCount = 0;     // 今日已预约数
}