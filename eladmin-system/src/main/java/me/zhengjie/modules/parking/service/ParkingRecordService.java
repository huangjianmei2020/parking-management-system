package me.zhengjie.modules.parking.service;

import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.modules.parking.dto.ParkingRecordQueryCriteria;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 停车记录管理 Service 接口
 */
public interface ParkingRecordService {

    @Transactional(rollbackFor = Exception.class)
    ParkingRecordDto confirmPay(Long id);

    /**
     * 分页查询停车记录
     */
    PageResult<ParkingRecordDto> queryAll(ParkingRecordQueryCriteria criteria, Pageable pageable);

    Map<String, Long> getCurrentCount();

    /**
     * 获取停车记录综合统计数据
     * @return 包含各项统计指标的Map
     */
    Map<String, Object> getStatistics();

    /**
     * 获取今日停车统计数据
     * @return 包含今日各项统计指标的Map
     */
    Map<String, Object> getTodayStatistics();

    /**
     * 获取仪表盘数据
     */
    Map<String, Object> getDashboardData();

    /**
     *自动分配车位入场
     */
    ParkingRecordDto autoAssignEntry(String plateNumber, Integer vehicleType);
    /**
     * 查询所有停车记录（不分页）
     */
    List<ParkingRecordDto> queryAll(ParkingRecordQueryCriteria criteria);

    /**
     * 根据ID查询停车记录
     */
    ParkingRecordDto findById(Long id);

    /**
     * 根据车牌号查询当前在场车辆
     */
    ParkingRecordDto findByPlateNumberAndStatus(String plateNumber, Integer status);

    /**
     * 创建停车记录（车辆入场）
     * @param plateNumber 车牌号
     * @param vehicleType 车辆类型
     * @param lotId 车位ID
     * @return 创建的停车记录DTO
     */
    ParkingRecordDto createEntry(String plateNumber, Integer vehicleType, Long lotId);

    /**
     * 车辆出场登记
     * @param id 停车记录ID
     * @return 更新后的停车记录DTO
     */
    ParkingRecordDto exitRecord(Long id);

    /**
     * 更新停车记录状态
     * @param id 记录ID
     * @param status 新状态
     */
    void updateStatus(Long id, Integer status);

    /**
     * 更新停车记录费用
     * @param id 记录ID
     * @param fee 费用金额
     * @param durationMinutes 停车时长（分钟）
     */
    @Transactional(rollbackFor = Exception.class)
    void updateFee(Long id, BigDecimal fee, Integer durationMinutes);

    /**
     * 查询当前在场车辆列表
     */
    List<ParkingRecordDto> getCurrentParkingRecords();

    /**
     * 按状态统计停车记录数量
     */
    Map<Integer, Long> getStatusCount();

    /**
     * 导出停车记录到Excel
     */
    void export(HttpServletResponse response, ParkingRecordQueryCriteria criteria) throws IOException;
}