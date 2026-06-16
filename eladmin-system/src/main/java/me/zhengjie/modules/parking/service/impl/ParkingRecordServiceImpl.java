package me.zhengjie.modules.parking.service.impl;

import java.text.SimpleDateFormat;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.parking.service.ParkingLotService;
import me.zhengjie.modules.parking.service.ParkingRecordService;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.modules.parking.dto.ParkingRecordQueryCriteria;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.RedisUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 停车记录管理 Service 实现类（Redis 版）
 */
@Service
@Slf4j
@AllArgsConstructor
public class ParkingRecordServiceImpl implements ParkingRecordService {

    private final RedisUtils redisUtils;
    private final ParkingLotService parkingLotService;

    /** Redis 中停车记录数据的 key 前缀 */
    private static final String RECORD_PREFIX = "parking:record:";

    /** 所有停车记录 ID 列表的 key */
    private static final String RECORD_IDS_KEY = "parking:record:ids";

    /** 车牌号索引的 key 前缀 */
    private static final String PLATE_INDEX = "parking:record:plate:";

    /** 在场车辆 ID 列表的 key */
    private static final String CURRENT_RECORDS_KEY = "parking:record:current";

    /** 缓存过期时间（秒），30天 */
    private static final long EXPIRATION_TIME = 30 * 24 * 60 * 60;

    @Override
    public Map<String, Long> getCurrentCount() {
        Map<String, Long> result = new HashMap<>();
        List<Long> currentIds = getCurrentIdsFromRedis();
        long currentCount = currentIds.size();
        long validCount = 0;
        for (Long id : currentIds) {
            ParkingRecordDto dto = getFromRedis(id);
            if (dto != null && dto.getStatus() == 0) {
                validCount++;
            }
        }

        result.put("currentCount", validCount);
        result.put("totalCurrentIds", currentCount);

        log.debug("获取当前在场车辆数量：{}", validCount);

        return result;
    }

    @Override
    public Map<String, Object> getTodayStatistics() {
        Map<String, Object> statistics = new HashMap<>();

        // 1. 获取所有记录
        List<ParkingRecordDto> allRecords = queryAll(new ParkingRecordQueryCriteria());
        Date now = new Date();

        // 2. 筛选今日的记录
        List<ParkingRecordDto> todayRecords = new ArrayList<>();
        for (ParkingRecordDto record : allRecords) {
            if (record.getEntryTime() != null && isSameDay(record.getEntryTime(), now)) {
                todayRecords.add(record);
            }
        }

        // 3. 今日基础统计
        long todayEntryCount = todayRecords.size();  // 今日入场总数

        long todayExitCount = 0;                     // 今日出场数
        long todayPaidCount = 0;                     // 今日已支付数
        long todaySettledCount = 0;                  // 今日已结算（未支付）数
        long todayCurrentCount = 0;                  // 今日入场且仍在场的数量

        BigDecimal todayIncome = BigDecimal.ZERO;     // 今日收入
        BigDecimal todayPendingIncome = BigDecimal.ZERO; // 今日待收费用

        for (ParkingRecordDto record : todayRecords) {
            // 统计出场
            if (record.getExitTime() != null && isSameDay(record.getExitTime(), now)) {
                todayExitCount++;

                // 统计支付状态
                if (record.getStatus() == 2) {
                    todayPaidCount++;
                    if (record.getFee() != null) {
                        todayIncome = todayIncome.add(record.getFee());
                    }
                } else if (record.getStatus() == 1) {
                    todaySettledCount++;
                    if (record.getFee() != null) {
                        todayPendingIncome = todayPendingIncome.add(record.getFee());
                    }
                }
            } else if (record.getStatus() == 0) {
                // 今日入场且仍在场
                todayCurrentCount++;
            }
        }

        statistics.put("todayEntryCount", todayEntryCount);
        statistics.put("todayExitCount", todayExitCount);
        statistics.put("todayPaidCount", todayPaidCount);
        statistics.put("todaySettledCount", todaySettledCount);
        statistics.put("todayCurrentCount", todayCurrentCount);
        statistics.put("todayIncome", todayIncome);
        statistics.put("todayPendingIncome", todayPendingIncome);

        // 4. 今日每小时入场分布
        Map<Integer, Long> hourlyEntryDistribution = new HashMap<>();
        for (int i = 0; i < 24; i++) {
            hourlyEntryDistribution.put(i, 0L);
        }

        for (ParkingRecordDto record : todayRecords) {
            if (record.getEntryTime() != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(record.getEntryTime());
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                hourlyEntryDistribution.merge(hour, 1L, Long::sum);
            }
        }
        statistics.put("hourlyEntryDistribution", hourlyEntryDistribution);

        // 5. 今日每小时出场分布
        Map<Integer, Long> hourlyExitDistribution = new HashMap<>();
        for (int i = 0; i < 24; i++) {
            hourlyExitDistribution.put(i, 0L);
        }

        for (ParkingRecordDto record : todayRecords) {
            if (record.getExitTime() != null && isSameDay(record.getExitTime(), now)) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(record.getExitTime());
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                hourlyExitDistribution.merge(hour, 1L, Long::sum);
            }
        }
        statistics.put("hourlyExitDistribution", hourlyExitDistribution);

        // 6. 今日车辆类型分布
        Map<String, Long> vehicleTypeDistribution = new HashMap<>();
        for (ParkingRecordDto record : todayRecords) {
            String typeName = convertVehicleType(record.getVehicleType());
            vehicleTypeDistribution.merge(typeName, 1L, Long::sum);
        }
        statistics.put("vehicleTypeDistribution", vehicleTypeDistribution);

        // 7. 今日停车时长统计
        long totalDurationMinutes = 0;
        long maxDurationMinutes = 0;
        int durationRecordCount = 0;

        for (ParkingRecordDto record : todayRecords) {
            if (record.getDurationMinutes() != null && record.getDurationMinutes() > 0) {
                totalDurationMinutes += record.getDurationMinutes();
                durationRecordCount++;
                if (record.getDurationMinutes() > maxDurationMinutes) {
                    maxDurationMinutes = record.getDurationMinutes();
                }
            }
        }

        long averageDurationMinutes = 0;
        if (durationRecordCount > 0) {
            averageDurationMinutes = totalDurationMinutes / durationRecordCount;
        }

        statistics.put("totalDurationMinutes", totalDurationMinutes);
        statistics.put("averageDurationMinutes", averageDurationMinutes);
        statistics.put("maxDurationMinutes", maxDurationMinutes);

        // 8. 今日收入趋势（每小时的收入累计）
        Map<Integer, BigDecimal> hourlyIncomeTrend = new HashMap<>();
        for (int i = 0; i < 24; i++) {
            hourlyIncomeTrend.put(i, BigDecimal.ZERO);
        }

        for (ParkingRecordDto record : todayRecords) {
            if (record.getStatus() == 2 && record.getFee() != null
                    && record.getExitTime() != null && isSameDay(record.getExitTime(), now)) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(record.getExitTime());
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                hourlyIncomeTrend.merge(hour, record.getFee(), BigDecimal::add);
            }
        }
        statistics.put("hourlyIncomeTrend", hourlyIncomeTrend);

        // 9. 当前高峰时段
        Map.Entry<Integer, Long> peakHour = hourlyEntryDistribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (peakHour != null && peakHour.getValue() > 0) {
            statistics.put("peakEntryHour", peakHour.getKey());
            statistics.put("peakEntryCount", peakHour.getValue());
        } else {
            statistics.put("peakEntryHour", -1);
            statistics.put("peakEntryCount", 0L);
        }

        log.info("获取今日统计成功：入场{}，出场{}，收入{}，在场{}",
                todayEntryCount, todayExitCount, todayIncome, todayCurrentCount);

        return statistics;
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> statistics = new HashMap<>();

        // 1. 获取所有记录
        List<ParkingRecordDto> allRecords = queryAll(new ParkingRecordQueryCriteria());
        Date now = new Date();

        // 2. 基础统计
        long totalRecords = allRecords.size();                    // 总记录数
        long currentParking = allRecords.stream()                 // 当前在场
                .filter(r -> r.getStatus() == 0)
                .count();
        long settledCount = allRecords.stream()                   // 已结算
                .filter(r -> r.getStatus() == 1)
                .count();
        long paidCount = allRecords.stream()                      // 已支付
                .filter(r -> r.getStatus() == 2)
                .count();

        statistics.put("totalRecords", totalRecords);
        statistics.put("currentParking", currentParking);
        statistics.put("settledCount", settledCount);
        statistics.put("paidCount", paidCount);

        // 3. 收入统计
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal todayIncome = BigDecimal.ZERO;
        BigDecimal monthIncome = BigDecimal.ZERO;
        BigDecimal averageFee = BigDecimal.ZERO;
        int paidRecordCount = 0;

        for (ParkingRecordDto record : allRecords) {
            if (record.getStatus() == 2 && record.getFee() != null) {
                totalIncome = totalIncome.add(record.getFee());
                paidRecordCount++;

                // 今日收入
                if (record.getExitTime() != null && isSameDay(record.getExitTime(), now)) {
                    todayIncome = todayIncome.add(record.getFee());
                }

                // 本月收入
                if (record.getExitTime() != null && isSameMonth(record.getExitTime(), now)) {
                    monthIncome = monthIncome.add(record.getFee());
                }
            }
        }

        // 计算平均费用
        if (paidRecordCount > 0) {
            averageFee = totalIncome.divide(new BigDecimal(paidRecordCount), 2, BigDecimal.ROUND_HALF_UP);
        }

        statistics.put("totalIncome", totalIncome);
        statistics.put("todayIncome", todayIncome);
        statistics.put("monthIncome", monthIncome);
        statistics.put("averageFee", averageFee);
        statistics.put("paidRecordCount", (long) paidRecordCount);

        // 4. 停车时长统计
        long totalDurationMinutes = 0;
        long maxDurationMinutes = 0;
        int durationRecordCount = 0;

        for (ParkingRecordDto record : allRecords) {
            if (record.getDurationMinutes() != null && record.getDurationMinutes() > 0) {
                totalDurationMinutes += record.getDurationMinutes();
                durationRecordCount++;
                if (record.getDurationMinutes() > maxDurationMinutes) {
                    maxDurationMinutes = record.getDurationMinutes();
                }
            }
        }

        long averageDurationMinutes = 0;
        if (durationRecordCount > 0) {
            averageDurationMinutes = totalDurationMinutes / durationRecordCount;
        }

        statistics.put("totalDurationMinutes", totalDurationMinutes);
        statistics.put("averageDurationMinutes", averageDurationMinutes);
        statistics.put("maxDurationMinutes", maxDurationMinutes);

        // 5. 车辆类型分布统计
        Map<Integer, Long> vehicleTypeDistribution = new HashMap<>();
        for (ParkingRecordDto record : allRecords) {
            Integer type = record.getVehicleType();
            if (type != null) {
                vehicleTypeDistribution.merge(type, 1L, Long::sum);
            }
        }

        Map<String, Long> vehicleTypeStats = new HashMap<>();
        vehicleTypeDistribution.forEach((type, count) -> {
            String typeName = convertVehicleType(type);
            vehicleTypeStats.put(typeName, count);
        });
        statistics.put("vehicleTypeDistribution", vehicleTypeStats);

        // 6. 每小时入场分布（统计每个小时的入场数量）
        Map<Integer, Long> hourlyDistribution = new HashMap<>();
        for (int i = 0; i < 24; i++) {
            hourlyDistribution.put(i, 0L);
        }

        for (ParkingRecordDto record : allRecords) {
            if (record.getEntryTime() != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(record.getEntryTime());
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                hourlyDistribution.merge(hour, 1L, Long::sum);
            }
        }
        statistics.put("hourlyDistribution", hourlyDistribution);

        // 7. 每周分布（统计每周几的入场数量）
        Map<String, Long> weeklyDistribution = new HashMap<>();
        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        for (String day : weekDays) {
            weeklyDistribution.put(day, 0L);
        }

        for (ParkingRecordDto record : allRecords) {
            if (record.getEntryTime() != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(record.getEntryTime());
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1; // Calendar.SUNDAY = 1
                String dayName = weekDays[dayOfWeek];
                weeklyDistribution.merge(dayName, 1L, Long::sum);
            }
        }
        statistics.put("weeklyDistribution", weeklyDistribution);

        // 8. 近7天入场趋势
        Map<String, Long> last7DaysTrend = new HashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd");
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);

        // 初始化近7天数据为0
        for (int i = 6; i >= 0; i--) {
            Calendar tempCal = Calendar.getInstance();
            tempCal.setTime(now);
            tempCal.add(Calendar.DAY_OF_YEAR, -i);
            String dateStr = sdf.format(tempCal.getTime());
            last7DaysTrend.put(dateStr, 0L);
        }

        // 统计近7天的入场记录
        for (ParkingRecordDto record : allRecords) {
            if (record.getEntryTime() != null) {
                Calendar tempCal = Calendar.getInstance();
                tempCal.setTime(now);
                tempCal.add(Calendar.DAY_OF_YEAR, -7);

                if (record.getEntryTime().after(tempCal.getTime())) {
                    String dateStr = sdf.format(record.getEntryTime());
                    last7DaysTrend.merge(dateStr, 1L, Long::sum);
                }
            }
        }
        statistics.put("last7DaysTrend", last7DaysTrend);

        log.info("获取停车记录统计成功：总记录{}，在场{}，已支付{}，总收入{}",
                totalRecords, currentParking, paidCount, totalIncome);

        return statistics;
    }
    @Override
    public Map<String, Object> getDashboardData() {
        Map<String, Object> data = new HashMap<>();

        // 1. 获取所有记录
        List<ParkingRecordDto> allRecords = queryAll(new ParkingRecordQueryCriteria());

        // 2. 获取当前时间
        Date now = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);

        // 3. 今日数据统计
        long todayEntryCount = 0;    // 今日入场数
        long todayExitCount = 0;     // 今日出场数
        BigDecimal todayIncome = BigDecimal.ZERO;  // 今日收入

        for (ParkingRecordDto record : allRecords) {
            // 统计今日入场
            if (isSameDay(record.getEntryTime(), now)) {
                todayEntryCount++;
            }

            // 统计今日出场（出场时间在今天）
            if (record.getExitTime() != null && isSameDay(record.getExitTime(), now)) {
                todayExitCount++;
                // 累计今日收入（已支付的记录）
                if (record.getStatus() == 2 && record.getFee() != null) {
                    todayIncome = todayIncome.add(record.getFee());
                }
            }
        }

        // 4. 当前在场车辆数
        long currentParkingCount = allRecords.stream()
                .filter(r -> r.getStatus() == 0)
                .count();

        // 5. 本月收入统计
        BigDecimal monthIncome = BigDecimal.ZERO;
        for (ParkingRecordDto record : allRecords) {
            if (record.getStatus() == 2 && record.getFee() != null
                    && record.getExitTime() != null && isSameMonth(record.getExitTime(), now)) {
                monthIncome = monthIncome.add(record.getFee());
            }
        }

        // 6. 总收入统计
        BigDecimal totalIncome = BigDecimal.ZERO;
        for (ParkingRecordDto record : allRecords) {
            if (record.getStatus() == 2 && record.getFee() != null) {
                totalIncome = totalIncome.add(record.getFee());
            }
        }

        // 7. 组装返回数据
        data.put("todayEntryCount", todayEntryCount);      // 今日入场数
        data.put("todayExitCount", todayExitCount);        // 今日出场数
        data.put("todayIncome", todayIncome);              // 今日收入
        data.put("currentParkingCount", currentParkingCount); // 当前在场数
        data.put("monthIncome", monthIncome);              // 本月收入
        data.put("totalIncome", totalIncome);              // 总收入
        data.put("totalRecords", (long) allRecords.size()); // 总记录数

        // 8. 车位统计数据（需要调用 ParkingLotService）
        try {
            Map<Integer, Long> lotStatusCount = parkingLotService.getStatusCount();
            long totalLots = lotStatusCount.values().stream().mapToLong(Long::longValue).sum();
            long availableLots = lotStatusCount.getOrDefault(0, 0L);
            data.put("totalLots", totalLots);              // 总车位数
            data.put("availableLots", availableLots);      // 空闲车位数
            data.put("occupiedLots", lotStatusCount.getOrDefault(1, 0L)); // 已占用车位数

            // 计算占用率
            if (totalLots > 0) {
                BigDecimal occupancyRate = new BigDecimal(availableLots)
                        .divide(new BigDecimal(totalLots), 4, BigDecimal.ROUND_HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(1, BigDecimal.ROUND_HALF_UP);
                data.put("occupancyRate", occupancyRate);  // 空闲率（%）
            } else {
                data.put("occupancyRate", BigDecimal.ZERO);
            }
        } catch (Exception e) {
            log.warn("获取车位统计数据失败", e);
            data.put("totalLots", 0);
            data.put("availableLots", 0);
            data.put("occupiedLots", 0);
            data.put("occupancyRate", BigDecimal.ZERO);
        }

        log.info("获取仪表盘数据成功：今日入场{}，今日出场{}，今日收入{}，当前在场{}",
                todayEntryCount, todayExitCount, todayIncome, currentParkingCount);

        return data;
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
     * 判断两个日期是否在同一月
     */
    private boolean isSameMonth(Date date1, Date date2) {
        if (date1 == null || date2 == null) {
            return false;
        }
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date1);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParkingRecordDto autoAssignEntry(String plateNumber, Integer vehicleType) {
        // 1. 校验车牌号是否已在场内
        ParkingRecordDto existing = findByPlateNumberAndStatus(plateNumber, 0);
        if (existing != null) {
            throw new BadRequestException("该车辆已在场内，车牌号：" + plateNumber);
        }

        // 2. 查找空闲车位（根据车辆类型匹配合适的车位）
        Long availableLotId = parkingLotService.findAvailableLotId(vehicleType);
        if (availableLotId == null) {
            throw new BadRequestException("暂无空闲车位，请稍后再试");
        }

        // 3. 检查并占用车位
        Integer status = parkingLotService.checkAndOccupy(availableLotId);
        if (status != 0) {
            throw new BadRequestException("车位已被占用，请重新选择");
        }

        // 4. 生成ID
        Long id = redisUtils.increment(RECORD_PREFIX + "id_gen");

        // 5. 创建停车记录
        ParkingRecordDto dto = new ParkingRecordDto();
        dto.setId(id);
        dto.setPlateNumber(plateNumber);
        dto.setVehicleType(vehicleType);
        dto.setLotId(availableLotId);
        dto.setEntryTime(new Date());
        dto.setStatus(0); // 在场
        dto.setStatusDesc("在场");

        // 6. 保存到Redis
        saveToRedis(dto);
        addIdToList(id);
        redisUtils.set(PLATE_INDEX + plateNumber, id, EXPIRATION_TIME, TimeUnit.SECONDS);
        addToCurrentList(id);

        // 7. 更新车位状态为已占用
        parkingLotService.updateStatus(availableLotId, 1);

        log.info("车辆自动入场成功：车牌号{}，分配车位{}", plateNumber, availableLotId);
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ParkingRecordDto confirmPay(Long id) {
        ParkingRecordDto dto = getFromRedis(id);
        if (dto == null) {
            throw new BadRequestException("停车记录不存在");
        }
        if (dto.getStatus() != 1) {
            throw new BadRequestException("该记录状态不允许确认支付，当前状态：" + dto.getStatusDesc());
        }

        // 更新状态为已支付
        dto.setStatus(2);
        dto.setStatusDesc("已支付");

        // 保存回 Redis
        saveToRedis(dto);
        removeFromCurrentList(id);

        // 释放车位
        if (dto.getLotId() != null) {
            parkingLotService.updateStatus(dto.getLotId(), 0);
        }

        log.info("确认支付成功：车牌号{}，费用{}", dto.getPlateNumber(), dto.getFee());
        return dto;
    }

    @Override
    public PageResult<ParkingRecordDto> queryAll(ParkingRecordQueryCriteria criteria, Pageable pageable) {
        List<ParkingRecordDto> allList = queryAll(criteria);
        return PageUtil.toPage(
                PageUtil.paging(pageable.getPageNumber(), pageable.getPageSize(), allList),
                allList.size()
        );
    }

    @Override
    public List<ParkingRecordDto> queryAll(ParkingRecordQueryCriteria criteria) {
        List<Long> ids = getIdsFromRedis();
        if (CollectionUtil.isEmpty(ids)) {
            return new ArrayList<>();
        }

        List<ParkingRecordDto> resultList = new ArrayList<>();
        for (Long id : ids) {
            ParkingRecordDto dto = getFromRedis(id);
            if (dto != null && matchCriteria(dto, criteria)) {
                resultList.add(dto);
            }
        }

        resultList.sort((o1, o2) -> o2.getId().compareTo(o1.getId()));
        return resultList;
    }

    @Override
    public ParkingRecordDto findById(Long id) {
        return getFromRedis(id);
    }

    @Override
    public ParkingRecordDto findByPlateNumberAndStatus(String plateNumber, Integer status) {
        if (StrUtil.isBlank(plateNumber)) {
            return null;
        }
        // 从车牌号索引中查找记录ID
        Long id = redisUtils.get(PLATE_INDEX + plateNumber, Long.class);
        if (id == null) {
            return null;
        }
        ParkingRecordDto dto = getFromRedis(id);
        if (dto != null && dto.getStatus().equals(status)) {
            return dto;
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParkingRecordDto createEntry(String plateNumber, Integer vehicleType, Long lotId) {
        // 1. 校验车牌号是否已在场内
        ParkingRecordDto existing = findByPlateNumberAndStatus(plateNumber, 0);
        if (existing != null) {
            throw new BadRequestException("该车辆已在场内，车牌号：" + plateNumber);
        }

        // 2. 校验车位是否存在且空闲
        if (lotId != null) {
            Integer status = parkingLotService.checkAndOccupy(lotId);
            if (status != 0) {
                throw new BadRequestException("车位「ID=" + lotId + "」当前状态为为非空闲，无法使用");
            }
        }

        // 3. 生成ID
        Long id = redisUtils.increment(RECORD_PREFIX + "id_gen");

        // 4. 创建停车记录
        ParkingRecordDto dto = new ParkingRecordDto();
        dto.setId(id);
        dto.setPlateNumber(plateNumber);
        dto.setVehicleType(vehicleType);
        dto.setLotId(lotId);
        dto.setEntryTime(new Date());
        dto.setStatus(0); // 在场
        dto.setStatusDesc("在场");

        // 5. 保存到Redis
        saveToRedis(dto);
        addIdToList(id);
        redisUtils.set(PLATE_INDEX + plateNumber, id, EXPIRATION_TIME, TimeUnit.SECONDS);
        addToCurrentList(id);

        // 6. 更新车位状态为已占用
        if (lotId != null) {
            parkingLotService.updateStatus(lotId, 1);
        }

        log.info("车辆入场成功：车牌号{}，车位{}", plateNumber, lotId);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParkingRecordDto exitRecord(Long id) {
        ParkingRecordDto dto = getFromRedis(id);
        if (dto == null) {
            throw new BadRequestException("停车记录不存在");
        }
        if (dto.getStatus() != 0) {
            throw new BadRequestException("该车辆已出场");
        }

        // 设置出场时间
        Date exitTime = new Date();
        dto.setExitTime(exitTime);

        // 计算停车时长（分钟）
        long durationMs = exitTime.getTime() - dto.getEntryTime().getTime();
        int durationMinutes = (int) (durationMs / (1000 * 60));
        dto.setDurationMinutes(durationMinutes);

        // 更新状态为已结算
        dto.setStatus(1);
        dto.setStatusDesc("已结算");

        // 保存回Redis
        saveToRedis(dto);
        removeFromCurrentList(id);

        log.info("车辆出场成功：车牌号{}，停车{}分钟", dto.getPlateNumber(), durationMinutes);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        ParkingRecordDto dto = getFromRedis(id);
        if (dto == null) {
            throw new BadRequestException("停车记录不存在");
        }

        dto.setStatus(status);
        switch (status) {
            case 0:
                dto.setStatusDesc("在场");
                break;
            case 1:
                dto.setStatusDesc("已结算");
                break;
            case 2:
                dto.setStatusDesc("已支付");
                break;
            default:
                dto.setStatusDesc("未知");
        }

        saveToRedis(dto);
        log.info("更新停车记录状态成功：ID={}，状态={}", id, status);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateFee(Long id, BigDecimal fee, Integer durationMinutes) {
        ParkingRecordDto dto = getFromRedis(id);
        if (dto == null) {
            throw new BadRequestException("停车记录不存在");
        }

        dto.setFee(fee);
        dto.setDurationMinutes(durationMinutes);
        saveToRedis(dto);

        log.info("更新停车记录费用成功：ID={}，费用={}，时长={}分钟", id, fee, durationMinutes);
    }

    @Override
    public List<ParkingRecordDto> getCurrentParkingRecords() {
        List<Long> currentIds = getCurrentIdsFromRedis();
        if (CollectionUtil.isEmpty(currentIds)) {
            return new ArrayList<>();
        }

        List<ParkingRecordDto> resultList = new ArrayList<>();
        for (Long id : currentIds) {
            ParkingRecordDto dto = getFromRedis(id);
            if (dto != null && dto.getStatus() == 0) {
                resultList.add(dto);
            }
        }

        resultList.sort((o1, o2) -> o2.getEntryTime().compareTo(o1.getEntryTime()));
        return resultList;
    }

    @Override
    public Map<Integer, Long> getStatusCount() {
        List<Long> ids = getIdsFromRedis();
        Map<Integer, Long> countMap = new HashMap<>();
        countMap.put(0, 0L); // 在场
        countMap.put(1, 0L); // 已结算
        countMap.put(2, 0L); // 已支付

        if (CollectionUtil.isEmpty(ids)) {
            return countMap;
        }

        for (Long id : ids) {
            ParkingRecordDto dto = getFromRedis(id);
            if (dto != null) {
                Long count = countMap.getOrDefault(dto.getStatus(), 0L);
                countMap.put(dto.getStatus(), count + 1);
            }
        }

        return countMap;
    }

    @Override
    public void export(HttpServletResponse response, ParkingRecordQueryCriteria criteria) throws IOException {
        List<ParkingRecordDto> list = queryAll(criteria);
        List<Map<String, Object>> dataList = new ArrayList<>();

        for (ParkingRecordDto dto : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("车牌号", dto.getPlateNumber());
            row.put("车辆类型", convertVehicleType(dto.getVehicleType()));
            row.put("入场时间", dto.getEntryTime());
            row.put("出场时间", dto.getExitTime());
            row.put("停车时长（分钟）", dto.getDurationMinutes());
            row.put("费用（元）", dto.getFee());
            row.put("状态", dto.getStatusDesc());
            dataList.add(row);
        }

        FileUtil.downloadExcel(dataList, response);
    }

    // ==================== 私有辅助方法 ====================

    private List<Long> getIdsFromRedis() {
        Object obj = redisUtils.get(RECORD_IDS_KEY);
        if (obj == null) {
            return new ArrayList<>();
        }
        return JSON.parseObject(obj.toString(), new TypeReference<List<Long>>() {});
    }

    private void addIdToList(Long id) {
        List<Long> ids = getIdsFromRedis();
        if (!ids.contains(id)) {
            ids.add(id);
            redisUtils.set(RECORD_IDS_KEY, ids, EXPIRATION_TIME, TimeUnit.SECONDS);
        }
    }

    private List<Long> getCurrentIdsFromRedis() {
        Object obj = redisUtils.get(CURRENT_RECORDS_KEY);
        if (obj == null) {
            return new ArrayList<>();
        }
        return JSON.parseObject(obj.toString(), new TypeReference<List<Long>>() {});
    }

    private void addToCurrentList(Long id) {
        List<Long> currentIds = getCurrentIdsFromRedis();
        if (!currentIds.contains(id)) {
            currentIds.add(id);
            redisUtils.set(CURRENT_RECORDS_KEY, currentIds, EXPIRATION_TIME, TimeUnit.SECONDS);
        }
    }

    private void removeFromCurrentList(Long id) {
        List<Long> currentIds = getCurrentIdsFromRedis();
        currentIds.remove(id);
        redisUtils.set(CURRENT_RECORDS_KEY, currentIds, EXPIRATION_TIME, TimeUnit.SECONDS);
    }

    private ParkingRecordDto getFromRedis(Long id) {
        if (id == null) {
            return null;
        }
        return redisUtils.get(RECORD_PREFIX + id, ParkingRecordDto.class);
    }

    private void saveToRedis(ParkingRecordDto dto) {
        if (dto == null || dto.getId() == null) {
            return;
        }
        redisUtils.set(RECORD_PREFIX + dto.getId(), dto, EXPIRATION_TIME, TimeUnit.SECONDS);
    }

    private boolean matchCriteria(ParkingRecordDto dto, ParkingRecordQueryCriteria criteria) {
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

        if (criteria.getVehicleType() != null) {
            if (!criteria.getVehicleType().equals(dto.getVehicleType())) {
                return false;
            }
        }

        return true;
    }

    private String convertVehicleType(Integer type) {
        if (type == null) return "";
        switch (type) {
            case 1: return "小型车";
            case 2: return "大型车";
            case 3: return "新能源";
            default: return "未知";
        }
    }
}