package me.zhengjie.modules.parking.dto;

import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.util.Date;

@Getter
@Setter
public class ParkingLotDto implements Serializable {
    private Long id;
    private String code;
    private String floor;
    private Integer type;
    private String typeDesc;      // 车位类型中文描述
    private Integer status;
    private String statusDesc;    // 状态中文描述
    private String createBy;
    private String updateBy;
    private Date createTime;
    private Date updateTime;
}