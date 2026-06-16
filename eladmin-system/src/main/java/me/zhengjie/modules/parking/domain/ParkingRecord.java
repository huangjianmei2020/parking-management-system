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
 * 停车记录实体类
 * 对应数据库表：parking_record
 */
@Entity
@Getter
@Setter
@Table(name = "parking_record")
public class ParkingRecord extends BaseEntity implements Serializable {

    @Id
    @Column(name = "record_id")
    @NotNull(groups = Update.class)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "ID", hidden = true)
    private Long id;

    @NotBlank
    @ApiModelProperty(value = "车牌号")
    private String plateNumber;

    @NotNull
    @ApiModelProperty(value = "车辆类型：1-小型车，2-大型车，3-新能源")
    private Integer vehicleType;

    @NotNull
    @ApiModelProperty(value = "关联车位ID")
    private Long lotId;

    @NotNull
    @ApiModelProperty(value = "入场时间")
    private Date entryTime;

    @ApiModelProperty(value = "出场时间（可为空，表示未出场）")
    private Date exitTime;

    @ApiModelProperty(value = "停车时长（分钟）")
    private Integer durationMinutes;

    @ApiModelProperty(value = "应收费用")
    private BigDecimal fee;

    @NotNull
    @ApiModelProperty(value = "记录状态：0-在场，1-已结算，2-已支付")
    private Integer status;

    @ApiModelProperty(value = "关联订单ID（可为空）")
    private Long orderId;

    /**
     * 构造方法：新建记录时默认状态为"在场"
     */
    public ParkingRecord() {
        this.status = 0;
        this.entryTime = new Date();
    }
}