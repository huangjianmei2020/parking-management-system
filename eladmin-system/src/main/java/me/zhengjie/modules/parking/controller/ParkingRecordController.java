package me.zhengjie.modules.parking.controller;

import cn.hutool.core.util.StrUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.parking.service.BaiduAiService;
import me.zhengjie.modules.parking.service.ParkingRecordService;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.modules.parking.dto.ParkingRecordQueryCriteria;
import me.zhengjie.modules.parking.utils.Base64ToMultipartFileConverter;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.Map;
/**
 * 停车记录管理 API 接口
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "停车场：停车记录管理")
@RequestMapping("/api/parkingRecord")
public class ParkingRecordController {

    private final ParkingRecordService parkingRecordService;
    private final BaiduAiService baiduAiService;
    // ==================== 查询接口 ====================

    @GetMapping
    @ApiOperation("查询停车记录列表")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<PageResult<ParkingRecordDto>> query(ParkingRecordQueryCriteria criteria, Pageable pageable) {
        return new ResponseEntity<>(parkingRecordService.queryAll(criteria, pageable), HttpStatus.OK);
    }

    @GetMapping("/all")
    @ApiOperation("查询所有停车记录（不分页）")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Object> queryAll(ParkingRecordQueryCriteria criteria) {
        return new ResponseEntity<>(parkingRecordService.queryAll(criteria), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @ApiOperation("查询停车记录详情")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<ParkingRecordDto> findById(@PathVariable Long id) {
        return new ResponseEntity<>(parkingRecordService.findById(id), HttpStatus.OK);
    }

    @GetMapping("/plate/{plateNumber}")
    @ApiOperation("根据车牌号查询在场记录")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<ParkingRecordDto> findByPlateNumber(@PathVariable String plateNumber) {
        ParkingRecordDto dto = parkingRecordService.findByPlateNumberAndStatus(plateNumber, 0);
        return new ResponseEntity<>(dto, dto != null ? HttpStatus.OK : HttpStatus.NOT_FOUND);
    }

    // ==================== 车辆入场 ====================

    @PostMapping("/entry")
    @ApiOperation("车辆入场登记")
    @Log("车辆入场登记")
    @PreAuthorize("@el.check('parkingRecord:entry')")
    public ResponseEntity<ParkingRecordDto> entry(@RequestBody @Valid EntryRequest request) {
        // 参数校验
        if (request.getAutoAssign() == null) {
            throw new BadRequestException("请指定是否自动分配车位");
        }
        ParkingRecordDto dto;
        if (request.getAutoAssign()) {
            // 自动分配车位：不需要 lotId，系统自动查找空闲车位
            dto = parkingRecordService.autoAssignEntry(
                    request.getPlateNumber(),
                    request.getVehicleType()
            );
        } else {
            // 手动选择车位：必须传入 lotId
            if (request.getLotId() == null) {
                throw new BadRequestException("手动选择车位模式下，请指定车位ID");
            }
            dto = parkingRecordService.createEntry(
                    request.getPlateNumber(),
                    request.getVehicleType(),
                    request.getLotId()
            );
        }
        return new ResponseEntity<>(dto, HttpStatus.CREATED);
    }

    @PostMapping("/entry/ai")
    @ApiOperation("AI车牌识别入场（上传图片自动识别车牌号）")
    @Log("AI车牌识别入场")
    @PreAuthorize("@el.check('parkingRecord:entry')")
    public ResponseEntity<ParkingRecordDto> aiEntry(@RequestParam("image") MultipartFile image,
                                                    @RequestParam("vehicleType") Integer vehicleType,
                                                    @RequestParam(value = "lotId", required = false) Long lotId) {
        // 1. 调用百度AI车牌识别
        String plateNumber = baiduAiService.recognizePlate(image);

        // 2. 自动完成入场
        ParkingRecordDto dto;
        if (lotId != null) {
            dto = parkingRecordService.createEntry(plateNumber, vehicleType, lotId);
        } else {
            dto = parkingRecordService.autoAssignEntry(plateNumber, vehicleType);
        }

        return new ResponseEntity<>(dto, HttpStatus.CREATED);
    }


    // ==================== 车辆出场 ====================

    @PostMapping("/exit")
    @ApiOperation("车辆出场登记")
    @Log("车辆出场登记")
    @PreAuthorize("@el.check('parkingRecord:exit')")
    public ResponseEntity<ParkingRecordDto> exit(@RequestBody @Valid ExitRequest request) {
        ParkingRecordDto dto;
        Long recordId ;
        if (StrUtil.isNotBlank(request.getPlateNumber())) {
            dto = parkingRecordService.findByPlateNumberAndStatus(request.getPlateNumber(),new Integer(0));
            recordId = dto.getId();
        } else if (request.getRecordId() != null) {
            recordId = request.getRecordId();
        }
        else {
            throw new BadRequestException("请提供记录ID或车牌号");
        }
        dto = parkingRecordService.exitRecord(recordId);
        return new ResponseEntity<>(dto, HttpStatus.OK);
    }

    @PostMapping("/confirmPay/{id}")
    @ApiOperation("确认支付完成（管理员线下收款后调用）")
    @Log("确认支付完成")
    @PreAuthorize("@el.check('parkingRecord:exit')")
    public ResponseEntity<ParkingRecordDto> confirmPay(@PathVariable Long id) {
        ParkingRecordDto dto = parkingRecordService.confirmPay(id);
        return new ResponseEntity<>(dto, HttpStatus.OK);
    }

    // ==================== 仪表盘接口 ====================

    @GetMapping("/dashboard")
    @ApiOperation("首页仪表盘数据")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = parkingRecordService.getDashboardData();
        return new ResponseEntity<>(dashboard, HttpStatus.OK);
    }
    // ==================== 在场车辆管理 ====================

    @GetMapping("/current")
    @ApiOperation("查询当前在场车辆列表")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Object> getCurrentRecords() {
        return new ResponseEntity<>(parkingRecordService.getCurrentParkingRecords(), HttpStatus.OK);
    }

    @GetMapping("/current/count")
    @ApiOperation("查询当前在场车辆数量")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Map<String, Long>> getCurrentCount() {
        return new ResponseEntity<>(parkingRecordService.getCurrentCount(), HttpStatus.OK);
    }

    // ==================== 统计接口 ====================

    @GetMapping("/statistics")
    @ApiOperation("停车记录统计")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return new ResponseEntity<>(parkingRecordService.getStatistics(), HttpStatus.OK);
    }

    @GetMapping("/statistics/status")
    @ApiOperation("按状态统计停车记录数量")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Map<Integer, Long>> getStatusCount() {
        return new ResponseEntity<>(parkingRecordService.getStatusCount(), HttpStatus.OK);
    }

    @GetMapping("/statistics/today")
    @ApiOperation("今日停车统计")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public ResponseEntity<Map<String, Object>> getTodayStatistics() {
        return new ResponseEntity<>(parkingRecordService.getTodayStatistics(), HttpStatus.OK);
    }

    // ==================== 导出接口 ====================

    @GetMapping("/export")
    @ApiOperation("导出停车记录")
    @Log("导出停车记录")
    @PreAuthorize("@el.check('parkingRecord:list')")
    public void export(HttpServletResponse response, ParkingRecordQueryCriteria criteria) throws IOException {
        parkingRecordService.export(response, criteria);
    }

    // ==================== 内部请求体类 ====================

    /**
     * 入场请求体
     */
    static class EntryRequest {
        private String plateNumber;   // 车牌号
        private Integer vehicleType;  // 车辆类型：1-小型车，2-大型车，3-新能源
        private Boolean autoAssign;   // 是否自动分配车位：true-自动分配，false-手动选择
        private Long lotId;           // 车位ID（可选）

        public String getPlateNumber() { return plateNumber; }
        public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
        public Integer getVehicleType() { return vehicleType; }
        public void setVehicleType(Integer vehicleType) { this.vehicleType = vehicleType; }
        public Boolean getAutoAssign() { return autoAssign; }
        public void setAutoAssign(Boolean autoAssign) { this.autoAssign = autoAssign; }
        public Long getLotId() { return lotId; }
        public void setLotId(Long lotId) { this.lotId = lotId; }
    }

    /**
     * 自动入场请求体
     */
    static class AutoEntryRequest {
        private String plateNumber;   // 车牌号
        private Integer vehicleType;  // 车辆类型：1-小型车，2-大型车，3-新能源

        public String getPlateNumber() { return plateNumber; }
        public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
        public Integer getVehicleType() { return vehicleType; }
        public void setVehicleType(Integer vehicleType) { this.vehicleType = vehicleType; }
    }
    static class ExitRequest {
        private Long recordId;          // 记录ID（从列表选择时传）
        private String plateNumber;     // 车牌号（直接输入时传）

        public Long getRecordId() {
            return recordId;
        }

        public void setRecordId(Long recordId) {
            this.recordId = recordId;
        }

        public String getPlateNumber() {
            return plateNumber;
        }

        public void setPlateNumber(String plateNumber) {
            this.plateNumber = plateNumber;
        }
    }
}