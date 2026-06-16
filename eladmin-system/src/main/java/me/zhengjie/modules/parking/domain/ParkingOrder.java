package me.zhengjie.modules.parking.domain;

import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import me.zhengjie.base.BaseEntity;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单实体类
 * 对应数据库表：parking_order
 */
@Entity
@Getter
@Setter
@Table(name = "parking_order")
public class ParkingOrder extends BaseEntity implements Serializable {

    @Id
    @Column(name = "order_id")
    @NotNull(groups = Update.class)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "ID", hidden = true)
    private Long id;

    @NotBlank
    @Column(unique = true)
    @ApiModelProperty(value = "订单号（唯一，业务编号）")
    private String orderNo;

    @NotNull
    @ApiModelProperty(value = "关联停车记录ID")
    private Long recordId;

    @NotNull
    @ApiModelProperty(value = "订单总金额")
    private BigDecimal totalAmount;

    @ApiModelProperty(value = "实际支付金额")
    private BigDecimal paidAmount;

    @NotNull
    @ApiModelProperty(value = "订单状态：0-待支付，1-已支付，2-已退款，3-部分退款")
    private Integer status;

    @ApiModelProperty(value = "支付渠道：wxpay / alipay")
    private String payChannel;

    @ApiModelProperty(value = "支付时间")
    private Date payTime;

    @ApiModelProperty(value = "支付过期时间")
    private Date expireTime;

    /**
     * 构造方法：新建订单时默认状态为"待支付"
     */
    public ParkingOrder() {
        this.status = 0;
    }
}