package me.zhengjie.modules.parking.service;

import me.zhengjie.modules.parking.dto.OrderDto;
import me.zhengjie.modules.parking.dto.OrderQueryCriteria;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 订单管理 Service 接口
 */
public interface OrderService {

    /**
     * 分页查询订单列表
     */
    PageResult<OrderDto> queryAll(OrderQueryCriteria criteria, Pageable pageable);

    /**
     * 查询所有订单（不分页）
     */
    List<OrderDto> queryAll(OrderQueryCriteria criteria);

    /**
     * 根据ID查询订单
     */
    OrderDto findById(Long id);

    /**
     * 根据订单号查询
     */
    OrderDto findByOrderNo(String orderNo);

    /**
     * 根据停车记录ID查询订单
     */
    OrderDto findByRecordId(Long recordId);

    /**
     * 创建订单（出场时调用）
     * @param recordId 停车记录ID
     * @return 创建的订单DTO
     */
    OrderDto createOrder(Long recordId);

    /**
     * 发起微信支付
     * @param orderId 订单ID
     * @return 包含支付二维码信息的Map
     */
    Map<String, Object> payWithWxpay(Long orderId);

    /**
     * 发起支付宝支付
     * @param orderId 订单ID
     * @return 包含支付链接信息的Map
     */
    Map<String, Object> payWithAlipay(Long orderId);

    /**
     * 处理微信支付回调通知
     * @param request HTTP请求（包含微信回调参数）
     * @return 返回给微信的响应字符串（SUCCESS/FAIL）
     */
    String handleWxpayNotify(HttpServletRequest request);

    /**
     * 处理支付宝支付回调通知
     * @param request HTTP请求（包含支付宝回调参数）
     * @return 返回给支付宝的响应字符串（success/fail）
     */
    String handleAlipayNotify(HttpServletRequest request);

    /**
     * 手动标记已支付（线下收款后管理员确认）
     */
    void manualPay(Long orderId);

    /**
     * 关闭订单（超时未支付）
     */
    void closeOrder(Long orderId);

    /**
     * 发起退款
     * @param orderId 订单ID
     * @param reason 退款原因
     */
    void refund(Long orderId, String reason);

    /**
     * 查询退款状态
     * @param orderId 订单ID
     * @return 退款状态信息
     */
    Map<String, Object> queryRefund(Long orderId);

    /**
     * 导出订单数据到Excel
     */
    void export(HttpServletResponse response, OrderQueryCriteria criteria) throws IOException;

    /**
     * 获取订单统计数据
     * @return 统计数据（订单总数、总金额等）
     */
    Map<String, Object> getStatistics();
}