package me.zhengjie.modules.parking.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.parking.domain.ParkingLot;
import me.zhengjie.modules.parking.service.ParkingLotService;
import me.zhengjie.modules.parking.service.impl.*;
import me.zhengjie.modules.parking.dto.ParkingLotDto;
import me.zhengjie.modules.parking.dto.ParkingLotQueryCriteria;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 车位管理 API 接口
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "停车场：车位管理")
@RequestMapping("/api/parkingLot")
public class ParkingLotController {

    private final ParkingLotService parkingLotService;

    @GetMapping
    @ApiOperation("查询车位列表")
    @PreAuthorize("@el.check('parkingLot:list')")
    public ResponseEntity<PageResult<ParkingLotDto>> query(ParkingLotQueryCriteria criteria, Pageable pageable) {
        return new ResponseEntity<>(parkingLotService.queryAll(criteria, pageable), HttpStatus.OK);
    }

    @GetMapping("/all")
    @ApiOperation("查询所有车位（不分页）")
    @PreAuthorize("@el.check('parkingLot:list')")
    public ResponseEntity<List<ParkingLotDto>> queryAll(ParkingLotQueryCriteria criteria) {
        return new ResponseEntity<>(parkingLotService.queryAll(criteria), HttpStatus.OK);
    }

    @PostMapping
    @ApiOperation("新增车位")
    @Log("新增车位")
    @PreAuthorize("@el.check('parkingLot:add')")
    public ResponseEntity<Object> create(@Validated @RequestBody ParkingLot resources) {
        // 校验车位编号唯一性
        if (parkingLotService.findByCode(resources.getCode()) != null) {
            throw new BadRequestException("车位编号已存在");
        }
        parkingLotService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @PutMapping
    @ApiOperation("修改车位")
    @Log("修改车位")
    @PreAuthorize("@el.check('parkingLot:edit')")
    public ResponseEntity<Object> update(@Validated(ParkingLot.Update.class) @RequestBody ParkingLot resources) {
        parkingLotService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @DeleteMapping
    @ApiOperation("删除车位")
    @Log("删除车位")
    @PreAuthorize("@el.check('parkingLot:del')")
    public ResponseEntity<Object> delete(@RequestBody Set<Long> ids) {
        // 校验车位是否为空闲状态
        for (Long id : ids) {
            ParkingLotDto parkingLot = parkingLotService.findById(id);
            if (parkingLot == null) {
                throw new BadRequestException("车位不存在，ID：" + id);
            }
            if (parkingLot.getStatus() != 0) {
                throw new BadRequestException("车位「" + parkingLot.getCode() + "」非空闲状态，无法删除");
            }
        }
        parkingLotService.delete(ids);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/import")
    @ApiOperation("批量导入车位")
    @Log("批量导入车位")
    @PreAuthorize("@el.check('parkingLot:add')")
    public ResponseEntity<Object> importExcel(@RequestParam("file") MultipartFile file) throws Exception {
        parkingLotService.importExcel(file);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @GetMapping("/export")
    @ApiOperation("导出车位数据")
    @Log("导出车位数据")
    @PreAuthorize("@el.check('parkingLot:list')")
    public void export(HttpServletResponse response, ParkingLotQueryCriteria criteria) throws IOException {
        parkingLotService.export(response, criteria);
    }

    @GetMapping("/statusCount")
    @ApiOperation("按状态统计车位数量")
    @PreAuthorize("@el.check('parkingLot:list')")
    public ResponseEntity<Object> getStatusCount() {
        return new ResponseEntity<>(parkingLotService.getStatusCount(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @ApiOperation("查询单个车位详情")
    @PreAuthorize("@el.check('parkingLot:list')")
    public ResponseEntity<ParkingLotDto> findById(@PathVariable Long id) {
        return new ResponseEntity<>(parkingLotService.findById(id), HttpStatus.OK);
    }
}