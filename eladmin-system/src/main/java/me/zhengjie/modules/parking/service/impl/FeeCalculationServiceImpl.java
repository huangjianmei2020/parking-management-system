package me.zhengjie.modules.parking.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.parking.service.FeeCalculationService;
import me.zhengjie.modules.parking.dto.FeeRuleDto;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.utils.RedisUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 计费计算 Service 实现类（Redis 版）
 */
@Service
@Slf4j
@AllArgsConstructor
public class FeeCalculationServiceImpl implements FeeCalculationService {

    private final RedisUtils redisUtils;

    /** Redis 中计费规则数据的 key 前缀 */
    private static final String FEE_RULE_PREFIX = "parking:feerule:";

    /** 所有计费规则 ID 列表的 key */
    private static final String FEE_RULE_IDS_KEY = "parking:feerule:ids";

    /** 当前生效规则的 key */
    private static final String ACTIVE_RULE_KEY = "parking:feerule:active";

    /** 缓存过期时间（秒），30天 */
    private static final long EXPIRATION_TIME = 30 * 24 * 60 * 60;

    /** 默认计费规则（当没有配置任何规则时使用） */
    private static final BigDecimal DEFAULT_UNIT_PRICE = new BigDecimal("5.00");
    private static final int DEFAULT_FREE_MINUTES = 15;
    private static final BigDecimal DEFAULT_DAILY_CAP = new BigDecimal("50.00");

    @Override
    public BigDecimal calculateFee(ParkingRecordDto recordDto) {
        if (recordDto == null) {
            throw new BadRequestException("停车记录不能为空");
        }

        // 获取停车时长（分钟）
        long diffMs = recordDto.getExitTime().getTime() - recordDto.getEntryTime().getTime();
        int durationMinute = (int) (diffMs / (1000 * 60));
        recordDto.setDurationMinutes(durationMinute);
        Integer durationMinutes = recordDto.getDurationMinutes();
        if (durationMinutes == null || durationMinutes <= 0) {
            return BigDecimal.ZERO;
        }
        // 根据规则计算费用
        return calculateByRule(durationMinutes);
    }

    @Override
    public BigDecimal calculateByRule(int durationMinutes) {
        FeeRuleDto ruleDto = new FeeRuleDto();
        BigDecimal unitPrice = ruleDto.getUnitPrice();
        int freeMinutes = ruleDto.getFreeMinutes();
        BigDecimal dailyCap = ruleDto.getDailyCap();

        // 1. 减去免费时长
        int billableMinutes = Math.max(0, durationMinutes - freeMinutes);
        if (billableMinutes == 0) {
            return BigDecimal.ZERO;
        }

        // 2. 按小时计费（不足一小时按一小时算）
        int hours = (int) Math.ceil(billableMinutes / 60.0);
        BigDecimal fee = unitPrice.multiply(new BigDecimal(hours));

        // 3. 应用每日封顶
        if (fee.compareTo(dailyCap) > 0) {
            fee = dailyCap;
        }

        // 4. 保留两位小数
        fee = fee.setScale(2, RoundingMode.HALF_UP);

        log.debug("计费计算：时长={}分钟，免费={}分钟，计费小时={}，单价={}，封顶={}，费用={}",
                durationMinutes, freeMinutes, hours, unitPrice, dailyCap, fee);

        return fee;
    }
}