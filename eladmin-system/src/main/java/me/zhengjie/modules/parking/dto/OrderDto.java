package me.zhengjie.modules.parking.dto;

import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
public class OrderDto implements Serializable {
    private Long id;
    private String orderNo;           // 订单号
    private Long recordId;            // 关联停车记录ID
    private String plateNumber;       // 车牌号（冗余，方便显示）
    private BigDecimal totalAmount;   // 订单总金额
    private BigDecimal paidAmount;    // 实际支付金额
    private Integer status;           // 订单状态
    private String statusDesc;        // 状态中文描述
    private String payChannel;        // 支付渠道
    private String payChannelDesc;    // 支付渠道中文描述
    private Date payTime;             // 支付时间
    private Date expireTime;          // 过期时间
    private Date createTime;          // 创建时间
}