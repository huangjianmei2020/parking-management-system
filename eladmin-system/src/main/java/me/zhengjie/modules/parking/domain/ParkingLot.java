package me.zhengjie.modules.parking.domain;

import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import me.zhengjie.base.BaseEntity;
import java.sql.Timestamp;
import javax.persistence.*;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
/**
 * 车位信息实体类
 * 对应数据库表：parking_lot
 */
@Entity
@Getter
@Setter
@Table(name = "parking_lot")
public class ParkingLot extends BaseEntity implements Serializable {

    @Id
    @Column(name = "lot_id")
    @NotNull(groups = Update.class)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "ID", hidden = true)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    @ApiModelProperty(value = "车位编号")
    private String code;

    @NotBlank
    @ApiModelProperty(value = "所属楼层/区域")
    private String floor;

    @NotNull
    @ApiModelProperty(value = "车位类型：1-小型车，2-大型车，3-新能源")
    private Integer type;

    @NotNull
    @ApiModelProperty(value = "状态：0-空闲，1-已占用，2-预留，3-维修中")
    private Integer status;

    @NotNull
    @ApiModelProperty(value = "该停车位创建的时间")
    private Timestamp createTime;
    /**
     * 构造方法：创建新车位时，默认状态为空闲
     */
    public ParkingLot() {
        this.status = 0;
    }
}