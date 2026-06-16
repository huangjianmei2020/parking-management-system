package me.zhengjie.modules.parking.service;

import me.zhengjie.modules.parking.domain.ParkingLot;
import me.zhengjie.modules.parking.dto.ParkingLotDto;
import me.zhengjie.modules.parking.dto.ParkingLotQueryCriteria;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 车位管理 Service 接口
 */
public interface ParkingLotService {

    /**
     * 分页查询车位列表
     * @param criteria 查询条件
     * @param pageable 分页参数
     * @return 分页结果
     */
    PageResult<ParkingLotDto> queryAll(ParkingLotQueryCriteria criteria, Pageable pageable);

    /**
     * 检查车位是否空闲并占用
     * 如果车位空闲则将其状态改为已占用，否则抛出异常
     * @param lotId 车位ID
     * @return Integer
     */
    Integer checkAndOccupy(Long lotId);

    /**
     * 查找空闲车位
     * @param vehicleType 车辆类型（用于匹配合适的车位类型）
     * @return 空闲车位的ID，如果没有空闲车位则返回null
     */
    Long findAvailableLotId(Integer vehicleType);

    /**
     * 查询所有车位（不分页）
     * @param criteria 查询条件
     * @return 车位列表
     */
    List<ParkingLotDto> queryAll(ParkingLotQueryCriteria criteria);

    /**
     * 根据ID查询单个车位
     * @param id 车位ID
     * @return 车位DTO
     */
    ParkingLotDto findById(Long id);

    /**
     * 根据ID获取实体
     * @param id 车位ID
     * @return 车位实体
     */
    ParkingLot findByIdentifier(Long id);

    /**
     * 根据车位编号查找
     * @param code 车位编号
     * @return 车位实体
     */
    ParkingLot findByCode(String code);

    /**
     * 创建车位
     * @param resources 车位实体
     * @return 创建后的车位实体
     */
    ParkingLot create(ParkingLot resources);

    /**
     * 更新车位
     * @param resources 车位实体
     */
    void update(ParkingLot resources);

    /**
     * 批量删除车位
     * @param ids 车位ID集合
     */
    void delete(Set<Long> ids);

    /**
     * 批量导入车位（Excel）
     * @param file Excel文件
     * @throws Exception 导入异常
     */
    void importExcel(MultipartFile file) throws Exception;

    /**
     * 导出车位数据到Excel
     * @param response HTTP响应
     * @param criteria 查询条件
     * @throws IOException IO异常
     */
    void export(HttpServletResponse response, ParkingLotQueryCriteria criteria) throws IOException;

    /**
     * 按状态统计车位数量
     * @return Map，key为状态码，value为数量
     */
    Map<Integer, Long> getStatusCount();

    /**
     * 更新车位状态
     * @param id 车位ID
     * @param status 新状态
     */
    void updateStatus(Long id, Integer status);
}