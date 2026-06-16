package me.zhengjie.modules.parking.dto;

import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.math.BigDecimal;

@Getter
@Setter
public class FeeRuleDto implements Serializable {
    private Long id;
    private String ruleName = "标准计费";          // 默认规则名称
    private BigDecimal unitPrice = new BigDecimal("5.00");     // 默认单价5元/小时
    private Integer freeMinutes = 30;              // 默认免费15分钟
    private BigDecimal dailyCap = new BigDecimal("50.00");    // 默认每日封顶50元
    private Boolean isActive = true;               // 默认启用
}