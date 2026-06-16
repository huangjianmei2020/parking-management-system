package me.zhengjie.modules.parking.dto;

import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
public class ParkingRecordDto implements Serializable {
    private Long id;
    private String plateNumber;       // 车牌号
    private Integer vehicleType;      // 车辆类型
    private String vehicleTypeDesc;   // 车辆类型中文描述
    private Long lotId;               // 关联车位ID
    private String lotCode;           // 车位编号（冗余）
    private Date entryTime;           // 入场时间
    private Date exitTime;            // 出场时间
    private Integer durationMinutes;  // 停车时长（分钟）
    private BigDecimal fee;           // 应收费用
    private Integer status;           // 记录状态：0-在场，1-已结算，2-已支付
    private String statusDesc;        // 状态中文描述
    private Long orderId;             // 关联订单ID
}