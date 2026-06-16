package me.zhengjie.modules.parking.dto;

import lombok.Data;
import me.zhengjie.annotation.Query;
import java.util.Date;

@Data
public class OrderQueryCriteria {

    @Query(type = Query.Type.INNER_LIKE)
    private String orderNo;           // 订单号模糊查询

    @Query(type = Query.Type.EQUAL)
    private Long recordId;            // 停车记录ID精确查询

    @Query(type = Query.Type.EQUAL)
    private Integer status;           // 订单状态精确查询

    @Query(type = Query.Type.EQUAL)
    private String payChannel;        // 支付渠道精确查询

    @Query(type = Query.Type.BETWEEN)
    private Date createTime;          // 创建时间范围查询
}