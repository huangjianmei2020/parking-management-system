package me.zhengjie.modules.parking.dto;

import lombok.Data;
import me.zhengjie.annotation.Query;

@Data
public class ParkingLotQueryCriteria {

    @Query(type = Query.Type.INNER_LIKE)
    private String code;          // 车位编号模糊查询

    @Query(type = Query.Type.EQUAL)
    private String floor;         // 楼层精确查询

    @Query(type = Query.Type.EQUAL)
    private Integer type;         // 车位类型精确查询

    @Query(type = Query.Type.EQUAL)
    private Integer status;       // 状态精确查询
}