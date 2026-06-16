package me.zhengjie.modules.parking.domain;

import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import me.zhengjie.base.BaseEntity;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 支付配置实体类
 * 对应数据库表：pay_config
 */
@Entity
@Getter
@Setter
@Table(name = "pay_config")
public class PayConfig extends BaseEntity implements Serializable {

    @Id
    @Column(name = "config_id")
    @NotNull(groups = Update.class)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "ID", hidden = true)
    private Long id;

    @NotBlank
    @ApiModelProperty(value = "渠道：wxpay / alipay")
    private String channel;

    @NotBlank
    @ApiModelProperty(value = "应用ID")
    private String appId;

    @NotBlank
    @ApiModelProperty(value = "商户号")
    private String mchId;

    @NotBlank
    @ApiModelProperty(value = "API密钥（加密存储）")
    private String apiKey;

    @NotBlank
    @ApiModelProperty(value = "回调通知地址")
    private String notifyUrl;

    @NotNull
    @ApiModelProperty(value = "是否启用：0-禁用，1-启用")
    private Boolean isActive;

    /**
     * 构造方法：新建配置时默认启用
     */
    public PayConfig() {
        this.isActive = true;
    }
}