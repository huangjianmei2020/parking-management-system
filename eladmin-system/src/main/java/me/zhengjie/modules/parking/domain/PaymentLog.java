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

/**
 * 支付流水实体类
 * 对应数据库表：payment_log
 */
@Entity
@Getter
@Setter
@Table(name = "payment_log")
public class PaymentLog extends BaseEntity implements Serializable {

    @Id
    @Column(name = "log_id")
    @NotNull(groups = Update.class)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "ID", hidden = true)
    private Long id;

    @NotNull
    @ApiModelProperty(value = "关联订单ID")
    private Long orderId;

    @ApiModelProperty(value = "微信/支付宝交易号（第三方返回）")
    private String transactionId;

    @NotBlank
    @ApiModelProperty(value = "商户订单号（与orderNo对应）")
    private String tradeNo;

    @NotNull
    @ApiModelProperty(value = "本次支付金额")
    private BigDecimal amount;

    @ApiModelProperty(value = "支付类型：jsapi / app / native / h5")
    private String payType;

    @NotBlank
    @ApiModelProperty(value = "流水状态：success / fail / refund")
    private String status;

    @Lob
    @Column(columnDefinition = "TEXT")
    @ApiModelProperty(value = "第三方返回的原始数据（JSON）")
    private String rawData;
}