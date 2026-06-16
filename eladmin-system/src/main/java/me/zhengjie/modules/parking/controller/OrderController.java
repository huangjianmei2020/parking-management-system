package me.zhengjie.modules.parking.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import me.zhengjie.annotation.Log;
import me.zhengjie.modules.parking.dto.OrderDto;
import me.zhengjie.modules.parking.service.*;
import me.zhengjie.modules.parking.dto.OrderQueryCriteria;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * 订单管理 API 接口
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "停车场：订单管理")
@RequestMapping("/api/parkingOrder")
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    @ApiOperation("查询订单列表")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<PageResult<OrderDto>> query(OrderQueryCriteria criteria, Pageable pageable) {
        return new ResponseEntity<>(orderService.queryAll(criteria, pageable), HttpStatus.OK);
    }

    @GetMapping("/all")
    @ApiOperation("查询所有订单（不分页）")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<Object> queryAll(OrderQueryCriteria criteria) {
        return new ResponseEntity<>(orderService.queryAll(criteria), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @ApiOperation("查询订单详情")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<OrderDto> findById(@PathVariable Long id) {
        return new ResponseEntity<>(orderService.findById(id), HttpStatus.OK);
    }

    @GetMapping("/orderNo/{orderNo}")
    @ApiOperation("根据订单号查询")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<OrderDto> findByOrderNo(@PathVariable String orderNo) {
        return new ResponseEntity<>(orderService.findByOrderNo(orderNo), HttpStatus.OK);
    }

    @GetMapping("/record/{recordId}")
    @ApiOperation("根据停车记录ID查询订单")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<OrderDto> findByRecordId(@PathVariable Long recordId) {
        return new ResponseEntity<>(orderService.findByRecordId(recordId), HttpStatus.OK);
    }

    @PostMapping("/create/{recordId}")
    @ApiOperation("创建订单（出场时调用）")
    @Log("创建订单")
    @PreAuthorize("@el.check('parkingOrder:add')")
    public ResponseEntity<OrderDto> createOrder(@PathVariable Long recordId) {
        OrderDto orderDto = orderService.createOrder(recordId);
        return new ResponseEntity<>(orderDto, HttpStatus.CREATED);
    }

    @PostMapping("/pay/wxpay/{orderId}")
    @ApiOperation("发起微信支付")
    @Log("发起微信支付")
    @PreAuthorize("@el.check('parkingOrder:pay')")
    public ResponseEntity<Object> payWithWxpay(@PathVariable Long orderId) {
        Map<String, Object> result = orderService.payWithWxpay(orderId);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PostMapping("/pay/alipay/{orderId}")
    @ApiOperation("发起支付宝支付")
    @Log("发起支付宝支付")
    @PreAuthorize("@el.check('parkingOrder:pay')")
    public ResponseEntity<Object> payWithAlipay(@PathVariable Long orderId) {
        Map<String, Object> result = orderService.payWithAlipay(orderId);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PostMapping("/notify/wxpay")
    @ApiOperation("微信支付回调通知")
    public ResponseEntity<String> wxpayNotify(HttpServletRequest request) {
        String result = orderService.handleWxpayNotify(request);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PostMapping("/notify/alipay")
    @ApiOperation("支付宝支付回调通知")
    public ResponseEntity<String> alipayNotify(HttpServletRequest request) {
        String result = orderService.handleAlipayNotify(request);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PostMapping("/manualPay/{orderId}")
    @ApiOperation("手动标记已支付（线下收款）")
    @Log("手动标记已支付")
    @PreAuthorize("@el.check('parkingOrder:edit')")
    public ResponseEntity<Object> manualPay(@PathVariable Long orderId) {
        orderService.manualPay(orderId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/close/{orderId}")
    @ApiOperation("关闭订单（超时未支付）")
    @Log("关闭订单")
    @PreAuthorize("@el.check('parkingOrder:edit')")
    public ResponseEntity<Object> closeOrder(@PathVariable Long orderId) {
        orderService.closeOrder(orderId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/refund/{orderId}")
    @ApiOperation("发起退款")
    @Log("发起退款")
    @PreAuthorize("@el.check('parkingOrder:refund')")
    public ResponseEntity<Object> refund(@PathVariable Long orderId, @RequestParam(required = false) String reason) {
        orderService.refund(orderId, reason);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/refund/query/{orderId}")
    @ApiOperation("查询退款状态")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<Object> queryRefund(@PathVariable Long orderId) {
        return new ResponseEntity<>(orderService.queryRefund(orderId), HttpStatus.OK);
    }

    @GetMapping("/export")
    @ApiOperation("导出订单数据")
    @Log("导出订单数据")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public void export(HttpServletResponse response, OrderQueryCriteria criteria) throws IOException {
        orderService.export(response, criteria);
    }

    @GetMapping("/statistics")
    @ApiOperation("订单统计")
    @PreAuthorize("@el.check('parkingOrder:list')")
    public ResponseEntity<Object> statistics() {
        return new ResponseEntity<>(orderService.getStatistics(), HttpStatus.OK);
    }
}