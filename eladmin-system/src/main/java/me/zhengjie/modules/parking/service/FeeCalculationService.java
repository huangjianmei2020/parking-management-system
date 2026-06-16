package me.zhengjie.modules.parking.service;

import me.zhengjie.modules.parking.dto.FeeRuleDto;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 计费计算 Service 接口
 */
public interface FeeCalculationService {

    /**
     * 计算停车费用
     *
     * @param recordDto 停车记录DTO
     * @return 应收费用
     */
    BigDecimal calculateFee(ParkingRecordDto recordDto);

    /**
     * 根据停车时长和计费规则计算费用
     *
     * @param durationMinutes 停车时长（分钟）
     * @param ruleDto         计费规则
     * @return 应收费用
     */
    BigDecimal calculateByRule(int durationMinutes);
}
