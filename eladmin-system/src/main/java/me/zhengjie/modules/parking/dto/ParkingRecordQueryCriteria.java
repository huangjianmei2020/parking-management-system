package me.zhengjie.modules.parking.dto;

import lombok.Data;

@Data
public class ParkingRecordQueryCriteria {
    private String plateNumber;       // 车牌号模糊查询
    private Integer status;           // 状态精确查询
    private Integer vehicleType;      // 车辆类型精确查询
}