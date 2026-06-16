package me.zhengjie.modules.parking.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.parking.controller.AppointmentController;
import me.zhengjie.modules.parking.dto.*;
import me.zhengjie.modules.parking.service.AppointmentService;
import me.zhengjie.modules.parking.service.ParkingLotService;
import me.zhengjie.modules.parking.service.ParkingRecordService;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.RedisUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 预约管理 Service 实现类（Redis 版）
 */
@Service
@Slf4j
@AllArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {

    private final RedisUtils redisUtils;
    private final ParkingLotService parkingLotService;
    private final ParkingRecordService parkingRecordService;

    /** Redis 中预约数据的 key 前缀 */
    private static final String APPOINT_PREFIX = "parking:appoint:";

    /** 所有预约 ID 列表的 key */
    private static final String APPOINT_IDS_KEY = "parking:appoint:ids";

    /** 车牌号预约索引的 key 前缀 */
    private static final String APPOINT_PLATE_INDEX = "parking:appoint:plate:";

    /** 预约配置 key */
    private static final String APPOINT_CONFIG_KEY = "parking:appoint:config";

    /** 缓存过期时间（秒），30天 */
    private static final long EXPIRATION_TIME = 30 * 24 * 60 * 60;

    /** 默认预约保留时长（分钟） */
    private static final int DEFAULT_RESERVE_MINUTES = 30;

    /** 默认单日最大预约数 */
    private static final int DEFAULT_MAX_APPOINT_PER_DAY = 200;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppointmentDto createAppointment(String plateNumber, Integer vehicleType, Long lotId) {
        // 1. 校验参数
        if (StrUtil.isBlank(plateNumber)) {
            throw new BadRequestException("车牌号不能为空");
        }
        if (vehicleType == null) {
            throw new BadRequestException("车辆类型不能为空");
        }

        // 2. 校验车牌号是否已存在有效预约
        Long existingAppointId = redisUtils.get(APPOINT_PLATE_INDEX + plateNumber, Long.class);
        if (existingAppointId != null) {
            AppointmentDto existingAppoint = getAppointFromRedis(existingAppointId);
            if (existingAppoint != null && existingAppoint.getStatus() == 0) {
                // 检查是否已过期
                if (existingAppoint.getExpireTime() != null && existingAppoint.getExpireTime().after(new Date())) {
                    throw new BadRequestException("该车辆已有有效预约，预约ID：" + existingAppointId + "，请先取消或等待过期");
                } else {
                    // 预约已过期，清理旧预约
                    existingAppoint.setStatus(3);
                    existingAppoint.setStatusDesc("已过期");
                    saveAppointToRedis(existingAppoint);
                    redisUtils.del(APPOINT_PLATE_INDEX + plateNumber);
                }
            }
        }

        // 3. 校验车牌号是否已在场内
        ParkingRecordDto existingRecord = parkingRecordService.findByPlateNumberAndStatus(plateNumber, 0);
        if (existingRecord != null) {
            throw new BadRequestException("该车辆已在场内，车牌号：" + plateNumber);
        }

        // 4. 检查每日预约上限
        checkDailyAppointLimit();

        // 5. 分配车位
        Long targetLotId = lotId;
        if (targetLotId == null) {
            // 自动分配空闲车位
            targetLotId = parkingLotService.findAvailableLotId(vehicleType);
            if (targetLotId == null) {
                throw new BadRequestException("暂无空闲车位可供预约");
            }
        } else {
            // 检查指定车位是否存在且空闲
            ParkingLotDto lotDto = parkingLotService.findById(targetLotId);
            if (lotDto == null) {
                throw new BadRequestException("车位不存在，ID：" + targetLotId);
            }
            if (lotDto.getStatus() != 0) {
                throw new BadRequestException("车位「" + lotDto.getCode() + "」当前状态为" +
                        convertLotStatus(lotDto.getStatus()) + "，无法预约");
            }
            // 检查车辆类型与车位类型是否兼容
            if (!isCompatible(vehicleType, lotDto.getType())) {
                throw new BadRequestException("车辆类型与车位类型不兼容");
            }
        }

        // 6. 获取预约配置
        AppointConfig config = getAppointConfig();
        int reserveMinutes = config != null ? config.getReserveMinutes() : DEFAULT_RESERVE_MINUTES;

        // 7. 生成预约ID
        Long appointId = redisUtils.increment(APPOINT_PREFIX + "id_gen");

        // 8. 计算过期时间
        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.MINUTE, reserveMinutes);
        Date expireTime = cal.getTime();

        // 9. 创建预约记录
        AppointmentDto dto = new AppointmentDto();
        dto.setId(appointId);
        dto.setPlateNumber(plateNumber);
        dto.setVehicleType(vehicleType);
        dto.setLotId(targetLotId);
        dto.setAppointTime(now);
        dto.setExpireTime(expireTime);
        dto.setStatus(0); // 有效
        dto.setStatusDesc("有效");

        // 10. 保存到Redis
        saveAppointToRedis(dto);
        addAppointIdToList(appointId);
        redisUtils.set(APPOINT_PLATE_INDEX + plateNumber, appointId, EXPIRATION_TIME, TimeUnit.SECONDS);

        // 11. 更新车位状态为已预约（状态2表示已预约）
        parkingLotService.updateStatus(targetLotId, 2);

        // 12. 设置 Redis 过期 Key，用于超时自动释放车位
        String timeoutKey = APPOINT_PREFIX + "timeout:" + appointId;
        redisUtils.set(timeoutKey, appointId, reserveMinutes * 60, TimeUnit.SECONDS);

        log.info("车位预约成功：车牌号{}，预约ID{}，车位ID{}，保留{}分钟，过期时间{}",
                plateNumber, appointId, targetLotId, reserveMinutes, expireTime);

        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAppointment(Long id) {
        AppointmentDto dto = getAppointFromRedis(id);
        if (dto == null) {
            throw new BadRequestException("预约记录不存在，ID：" + id);
        }
        if (dto.getStatus() != 0) {
            throw new BadRequestException("非预约状态不允许取消，当前状态：" + dto.getStatusDesc());
        }

        // 检查是否超过取消时限
        AppointConfig config = getAppointConfig();
        int cancelLimitMinutes = config != null ? config.getCancelLimitMinutes() : 5;

        Date now = new Date();
        long elapsedMinutes = (now.getTime() - dto.getAppointTime().getTime()) / (1000 * 60);

        if (elapsedMinutes >= cancelLimitMinutes) {
            throw new BadRequestException("预约已超过" + cancelLimitMinutes + "分钟，无法取消");
        }

        // 更新状态为已取消
        dto.setStatus(2);
        dto.setStatusDesc("已取消");
        saveAppointToRedis(dto);

        // 释放车位
        if (dto.getLotId() != null) {
            parkingLotService.updateStatus(dto.getLotId(), 0);
        }

        // 清除车牌索引
        redisUtils.del(APPOINT_PLATE_INDEX + dto.getPlateNumber());

        // 清除 Redis 过期 Key，避免不必要的通知
        String timeoutKey = APPOINT_PREFIX + "timeout:" + id;
        redisUtils.del(timeoutKey);

        log.info("预约已取消：预约ID{}，车牌号{}", id, dto.getPlateNumber());
    }

    @Override
    public PageResult<AppointmentDto> queryAll(AppointmentQueryCriteria criteria, Pageable pageable) {
        List<AppointmentDto> allList = queryAll(criteria);
        return PageUtil.toPage(
                PageUtil.paging(pageable.getPageNumber(), pageable.getPageSize(), allList),
                allList.size()
        );
    }

    @Override
    public List<AppointmentDto> queryAll(AppointmentQueryCriteria criteria) {
        List<Long> appointIds = getAppointIdsFromRedis();
        if (CollectionUtil.isEmpty(appointIds)) {
            return new ArrayList<>();
        }

        List<AppointmentDto> resultList = new ArrayList<>();
        for (Long id : appointIds) {
            AppointmentDto dto = getAppointFromRedis(id);
            if (dto != null && matchCriteria(dto, criteria)) {
                resultList.add(dto);
            }
        }

        resultList.sort((o1, o2) -> o2.getAppointTime().compareTo(o1.getAppointTime()));
        return resultList;
    }

    @Override
    public List<AppointmentDto> getCurrentAppointments() {
        List<Long> appointIds = getAppointIdsFromRedis();
        if (CollectionUtil.isEmpty(appointIds)) {
            return new ArrayList<>();
        }

        List<AppointmentDto> resultList = new ArrayList<>();
        Date now = new Date();

        for (Long id : appointIds) {
            AppointmentDto dto = getAppointFromRedis(id);
            if (dto != null && dto.getStatus() == 0 && dto.getExpireTime() != null
                    && dto.getExpireTime().after(now)) {
                resultList.add(dto);
            }
        }

        resultList.sort((o1, o2) -> o2.getAppointTime().compareTo(o1.getAppointTime()));
        return resultList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParkingRecordDto arriveAndEntry(Long id) {
        AppointmentDto appoint = getAppointFromRedis(id);
        if (appoint == null) {
            throw new BadRequestException("预约记录不存在，ID：" + id);
        }
        if (appoint.getStatus() != 0) {
            throw new BadRequestException("预约状态不允许入场，当前状态：" + appoint.getStatusDesc());
        }

        // 检查是否超时
        Date now = new Date();
        if (appoint.getExpireTime() != null && appoint.getExpireTime().before(now)) {
            // 超时了，自动释放
            appoint.setStatus(3);
            appoint.setStatusDesc("已过期");
            saveAppointToRedis(appoint);

            if (appoint.getLotId() != null) {
                parkingLotService.updateStatus(appoint.getLotId(), 0);
            }

            redisUtils.del(APPOINT_PLATE_INDEX + appoint.getPlateNumber());

            throw new BadRequestException("预约已超时，请重新预约");
        }

        // 更新预约状态为已到场
        appoint.setStatus(1);
        appoint.setStatusDesc("已到场");
        saveAppointToRedis(appoint);

        // 创建正式入场记录（使用预约的车位）
        ParkingRecordDto record = parkingRecordService.createEntry(
                appoint.getPlateNumber(),
                appoint.getVehicleType(),
                appoint.getLotId()
        );

        // 清除车牌预约索引
        redisUtils.del(APPOINT_PLATE_INDEX + appoint.getPlateNumber());

        // 清除 Redis 过期 Key，避免不必要的通知
        String timeoutKey = APPOINT_PREFIX + "timeout:" + id;
        redisUtils.del(timeoutKey);

        log.info("预约到场成功：预约ID{}，车牌号{}，转入记录ID{}",
                id, appoint.getPlateNumber(), record.getId());

        return record;
    }

    @Override
    public void updateConfig(AppointmentController.AppointConfigRequest request) {
        AppointConfig config = getAppointConfig();
        if (config == null) {
            config = new AppointConfig();
        }

        if (request.getReserveMinutes() != null) {
            config.setReserveMinutes(request.getReserveMinutes());
        }
        if (request.getMaxAppointPerDay() != null) {
            config.setMaxAppointPerDay(request.getMaxAppointPerDay());
        }
        if (request.getCancelLimitMinutes() != null) {
            config.setCancelLimitMinutes(request.getCancelLimitMinutes());
        }

        redisUtils.set(APPOINT_CONFIG_KEY, config, EXPIRATION_TIME, TimeUnit.SECONDS);

        log.info("更新预约配置成功：保留{}分钟，每日上限{}，取消时限{}分钟",
                config.getReserveMinutes(), config.getMaxAppointPerDay(), config.getCancelLimitMinutes());
    }

    @Override
    public AppointConfigDto getConfig() {
        AppointConfig config = getAppointConfig();
        AppointConfigDto dto = new AppointConfigDto();

        if (config != null) {
            dto.setReserveMinutes(config.getReserveMinutes());
            dto.setMaxAppointPerDay(config.getMaxAppointPerDay());
            dto.setCancelLimitMinutes(config.getCancelLimitMinutes());
        } else {
            dto.setReserveMinutes(DEFAULT_RESERVE_MINUTES);
            dto.setMaxAppointPerDay(DEFAULT_MAX_APPOINT_PER_DAY);
            dto.setCancelLimitMinutes(5);
        }

        // 统计今日预约数
        List<Long> appointIds = getAppointIdsFromRedis();
        Date today = new Date();
        long todayCount = 0;
        for (Long appointId : appointIds) {
            AppointmentDto appointment = getAppointFromRedis(appointId);
            if (appointment != null && appointment.getAppointTime() != null
                    && isSameDay(appointment.getAppointTime(), today)) {
                todayCount++;
            }
        }
        dto.setTodayAppointCount((int) todayCount);
        return dto;
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> statistics = new HashMap<>();
        List<Long> appointIds = getAppointIdsFromRedis();

        long totalCount = appointIds.size();
        long validCount = 0;
        long arrivedCount = 0;
        long cancelledCount = 0;
        long expiredCount = 0;
        Date now = new Date();

        for (Long id : appointIds) {
            AppointmentDto dto = getAppointFromRedis(id);
            if (dto != null) {
                switch (dto.getStatus()) {
                    case 0:
                        if (dto.getExpireTime() != null && dto.getExpireTime().after(now)) {
                            validCount++;
                        } else {
                            expiredCount++;
                        }
                        break;
                    case 1:
                        arrivedCount++;
                        break;
                    case 2:
                        cancelledCount++;
                        break;
                    case 3:
                        expiredCount++;
                        break;
                }
            }
        }

        statistics.put("totalCount", totalCount);
        statistics.put("validCount", validCount);
        statistics.put("arrivedCount", arrivedCount);
        statistics.put("cancelledCount", cancelledCount);
        statistics.put("expiredCount", expiredCount);

        log.info("获取预约统计成功：总数{}，有效{}，已到场{}，已取消{}，已过期{}",
                totalCount, validCount, arrivedCount, cancelledCount, expiredCount);

        return statistics;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从Redis获取预约记录
     */
    private AppointmentDto getAppointFromRedis(Long id) {
        if (id == null) {
            return null;
        }
        return redisUtils.get(APPOINT_PREFIX + id, AppointmentDto.class);
    }

    /**
     * 保存预约记录到Redis
     */
    private void saveAppointToRedis(AppointmentDto dto) {
        if (dto == null || dto.getId() == null) {
            return;
        }
        redisUtils.set(APPOINT_PREFIX + dto.getId(), dto, EXPIRATION_TIME, TimeUnit.SECONDS);
    }

    /**
     * 获取所有预约ID列表
     */
    private List<Long> getAppointIdsFromRedis() {
        Object obj = redisUtils.get(APPOINT_IDS_KEY);
        if (obj == null) {
            return new ArrayList<>();
        }
        return JSON.parseObject(obj.toString(), new TypeReference<List<Long>>() {});
    }

    /**
     * 添加预约ID到列表
     */
    private void addAppointIdToList(Long id) {
        List<Long> ids = getAppointIdsFromRedis();
        if (!ids.contains(id)) {
            ids.add(id);
            redisUtils.set(APPOINT_IDS_KEY, ids, EXPIRATION_TIME, TimeUnit.SECONDS);
        }
    }

    /**
     * 从列表移除预约ID
     */
    private void removeAppointIdFromList(Long id) {
        List<Long> ids = getAppointIdsFromRedis();
        ids.remove(id);
        redisUtils.set(APPOINT_IDS_KEY, ids, EXPIRATION_TIME, TimeUnit.SECONDS);
    }

    /**
     * 检查每日预约上限
     */
    private void checkDailyAppointLimit() {
        AppointConfig config = getAppointConfig();
        int maxPerDay = config != null ? config.getMaxAppointPerDay() : DEFAULT_MAX_APPOINT_PER_DAY;

        List<Long> appointIds = getAppointIdsFromRedis();
        Date today = new Date();
        long todayCount = 0;

        for (Long id : appointIds) {
            AppointmentDto dto = getAppointFromRedis(id);
            if (dto != null && dto.getAppointTime() != null && isSameDay(dto.getAppointTime(), today)) {
                todayCount++;
            }
        }

        if (todayCount >= maxPerDay) {
            throw new BadRequestException("今日预约已达上限（" + maxPerDay + "个），请明天再试");
        }
    }

    /**
     * 获取预约配置
     */
    private AppointConfig getAppointConfig() {
        return redisUtils.get(APPOINT_CONFIG_KEY, AppointConfig.class);
    }

    /**
     * 判断两个日期是否是同一天
     */
    private boolean isSameDay(Date date1, Date date2) {
        if (date1 == null || date2 == null) {
            return false;
        }
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date1);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 判断车辆类型是否与车位类型兼容（复用ParkingLotServiceImpl的逻辑）
     */
    private boolean isCompatible(Integer vehicleType, Integer lotType) {
        if (vehicleType == null || lotType == null) {
            return true;
        }
        switch (vehicleType) {
            case 1: // 小型车：可以停小型车位和新能源车位
                return lotType == 1 || lotType == 3;
            case 2: // 大型车：只能停大型车位
                return lotType == 2;
            case 3: // 新能源车：可以停新能源车位和小型车位
                return lotType == 3 || lotType == 1;
            default:
                return false;
        }
    }

    /**
     * 将车位状态数字转为中文描述
     */
    private String convertLotStatus(Integer status) {
        if (status == null) return "";
        switch (status) {
            case 0: return "空闲";
            case 1: return "已占用";
            case 2: return "已预约";
            case 3: return "维修中";
            default: return "未知";
        }
    }

    /**
     * 匹配查询条件
     */
    private boolean matchCriteria(AppointmentDto dto, AppointmentQueryCriteria criteria) {
        if (criteria == null) {
            return true;
        }

        if (StrUtil.isNotBlank(criteria.getPlateNumber())) {
            if (!dto.getPlateNumber().contains(criteria.getPlateNumber())) {
                return false;
            }
        }

        if (criteria.getStatus() != null) {
            if (!criteria.getStatus().equals(dto.getStatus())) {
                return false;
            }
        }

        if (criteria.getLotId() != null) {
            if (!criteria.getLotId().equals(dto.getLotId())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 预约配置内部类
     */
    private static class AppointConfig {
        private Integer reserveMinutes = DEFAULT_RESERVE_MINUTES;
        private Integer maxAppointPerDay = DEFAULT_MAX_APPOINT_PER_DAY;
        private Integer cancelLimitMinutes = 5;

        public Integer getReserveMinutes() { return reserveMinutes; }
        public void setReserveMinutes(Integer reserveMinutes) { this.reserveMinutes = reserveMinutes; }
        public Integer getMaxAppointPerDay() { return maxAppointPerDay; }
        public void setMaxAppointPerDay(Integer maxAppointPerDay) { this.maxAppointPerDay = maxAppointPerDay; }
        public Integer getCancelLimitMinutes() { return cancelLimitMinutes; }
        public void setCancelLimitMinutes(Integer cancelLimitMinutes) { this.cancelLimitMinutes = cancelLimitMinutes; }
    }
}