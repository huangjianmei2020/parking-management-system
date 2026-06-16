package me.zhengjie.modules.parking.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import me.zhengjie.annotation.Log;
import me.zhengjie.modules.parking.dto.AppointConfigDto;
import me.zhengjie.modules.parking.dto.AppointmentDto;
import me.zhengjie.modules.parking.dto.AppointmentQueryCriteria;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.modules.parking.service.AppointmentService;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 车位预约管理 API 接口
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "停车场：车位预约管理")
@RequestMapping("/api/appointment")
@Validated
public class AppointmentController {

    private final AppointmentService appointmentService;

    // ==================== 预约操作 ====================

    @PostMapping("/create")
    @ApiOperation("创建预约")
    @Log("创建预约")
    @PreAuthorize("@el.check('appointment:create')")
    public ResponseEntity<AppointmentDto> create(@RequestBody @Valid CreateAppointRequest request) {
        AppointmentDto dto = appointmentService.createAppointment(
                request.getPlateNumber(),
                request.getVehicleType(),
                request.getLotId()  // 可为null，系统自动分配
        );
        return new ResponseEntity<>(dto, HttpStatus.CREATED);
    }

    @PostMapping("/cancel/{id}")
    @ApiOperation("取消预约")
    @Log("取消预约")
    @PreAuthorize("@el.check('appointment:cancel')")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        appointmentService.cancelAppointment(id);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    // ==================== 查询接口 ====================

    @GetMapping("/list")
    @ApiOperation("查询所有预约记录（分页）")
    @PreAuthorize("@el.check('appointment:list')")
    public ResponseEntity<PageResult<AppointmentDto>> list(AppointmentQueryCriteria criteria, Pageable pageable) {
        return new ResponseEntity<>(appointmentService.queryAll(criteria, pageable), HttpStatus.OK);
    }

    @GetMapping("/current")
    @ApiOperation("查询当前有效预约")
    @PreAuthorize("@el.check('appointment:list')")
    public ResponseEntity<List<AppointmentDto>> getCurrent() {
        return new ResponseEntity<>(appointmentService.getCurrentAppointments(), HttpStatus.OK);
    }

    // ==================== 到场操作 ====================

    @PostMapping("/arrive/{id}")
    @ApiOperation("确认到场（预约入场）")
    @Log("确认到场")
    @PreAuthorize("@el.check('appointment:arrive')")
    public ResponseEntity<ParkingRecordDto> arrive(@PathVariable Long id) {
        ParkingRecordDto record = appointmentService.arriveAndEntry(id);
        return new ResponseEntity<>(record, HttpStatus.CREATED);
    }

    // ==================== 配置管理 ====================

    @PutMapping("/config")
    @ApiOperation("配置预约参数")
    @Log("配置预约参数")
    @PreAuthorize("@el.check('appointment:config')")
    public ResponseEntity<Void> updateConfig(@RequestBody @Valid AppointConfigRequest request) {
        appointmentService.updateConfig(request);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping("/config")
    @ApiOperation("获取预约配置")
    @PreAuthorize("@el.check('appointment:list')")
    public ResponseEntity<AppointConfigDto> getConfig() {
        return new ResponseEntity<>(appointmentService.getConfig(), HttpStatus.OK);
    }

    // ==================== 统计接口 ====================

    @GetMapping("/statistics")
    @ApiOperation("预约统计")
    @PreAuthorize("@el.check('appointment:list')")
    public ResponseEntity<Map<String, Object>> statistics() {
        return new ResponseEntity<>(appointmentService.getStatistics(), HttpStatus.OK);
    }

    // ==================== 内部请求体类 ====================

    /**
     * 创建预约请求体
     */
    static class CreateAppointRequest {
        @NotBlank(message = "车牌号不能为空")
        private String plateNumber;      // 车牌号
        @NotNull(message = "车辆类型不能为空")
        private Integer vehicleType;     // 车辆类型：1-小型车，2-大型车，3-新能源
        private Long lotId;              // 指定车位ID（可选，为空则自动分配）

        public String getPlateNumber() { return plateNumber; }
        public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
        public Integer getVehicleType() { return vehicleType; }
        public void setVehicleType(Integer vehicleType) { this.vehicleType = vehicleType; }
        public Long getLotId() { return lotId; }
        public void setLotId(Long lotId) { this.lotId = lotId; }
    }

    /**
     * 预约参数配置请求体
     */
    public static class AppointConfigRequest {
        private Integer reserveMinutes;      // 预约保留时长（分钟），默认15
        private Integer maxAppointPerDay;    // 单日最大预约数，默认50
        private Integer cancelLimitMinutes;  // 取消预约时限（分钟），超过不可取消

        public Integer getReserveMinutes() { return reserveMinutes; }
        public void setReserveMinutes(Integer reserveMinutes) { this.reserveMinutes = reserveMinutes; }
        public Integer getMaxAppointPerDay() { return maxAppointPerDay; }
        public void setMaxAppointPerDay(Integer maxAppointPerDay) { this.maxAppointPerDay = maxAppointPerDay; }
        public Integer getCancelLimitMinutes() { return cancelLimitMinutes; }
        public void setCancelLimitMinutes(Integer cancelLimitMinutes) { this.cancelLimitMinutes = cancelLimitMinutes; }
    }
}