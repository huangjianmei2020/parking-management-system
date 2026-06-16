package me.zhengjie.modules.parking.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.parking.dto.OrderDto;
import me.zhengjie.modules.parking.dto.OrderQueryCriteria;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.modules.parking.service.*;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.RedisUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单管理 Service 实现类（Redis 版）
 */
@Service
@Slf4j
@AllArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final RedisUtils redisUtils;
    private final ParkingRecordService parkingRecordService;
    private final ParkingLotService parkingLotService;
    private final FeeCalculationService feeCalculationService;

    /** Redis 中订单数据的 key 前缀 */
    private static final String ORDER_PREFIX = "parking:order:";

    /** 所有订单 ID 列表的 key */
    private static final String ORDER_IDS_KEY = "parking:order:ids";

    /** 订单号索引的 key 前缀 */
    private static final String ORDER_NO_INDEX = "parking:order:no:";

    /** 停车记录ID到订单ID的索引 */
    private static final String RECORD_ORDER_INDEX = "parking:order:record:";

    /** 缓存过期时间（秒），30天 */
    private static final long EXPIRATION_TIME = 30 * 24 * 60 * 60;

    /** 支付过期时间（分钟） */
    private static final long PAY_EXPIRE_MINUTES = 30;

    @Override
    public PageResult<OrderDto> queryAll(OrderQueryCriteria criteria, Pageable pageable) {
        List<OrderDto> allList = queryAll(criteria);
        return PageUtil.toPage(
                PageUtil.paging(pageable.getPageNumber(), pageable.getPageSize(), allList),
                allList.size()
        );
    }

    @Override
    public List<OrderDto> queryAll(OrderQueryCriteria criteria) {
        List<Long> ids = getIdsFromRedis();
        if (CollectionUtil.isEmpty(ids)) {
            return new ArrayList<>();
        }

        List<OrderDto> resultList = new ArrayList<>();
        for (Long id : ids) {
            OrderDto dto = getFromRedis(id);
            if (dto != null && matchCriteria(dto, criteria)) {
                resultList.add(dto);
            }
        }

        resultList.sort((o1, o2) -> o2.getId().compareTo(o1.getId()));
        return resultList;
    }

    @Override
    public OrderDto findById(Long id) {
        return getFromRedis(id);
    }

    @Override
    public OrderDto findByOrderNo(String orderNo) {
        if (StrUtil.isBlank(orderNo)) {
            return null;
        }
        Long id = redisUtils.get(ORDER_NO_INDEX + orderNo, Long.class);
        return id == null ? null : getFromRedis(id);
    }

    @Override
    public OrderDto findByRecordId(Long recordId) {
        if (recordId == null) {
            return null;
        }
        Long id = redisUtils.get(RECORD_ORDER_INDEX + recordId, Long.class);
        return id == null ? null : getFromRedis(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderDto createOrder(Long recordId) {
        // 1. 获取停车记录
        ParkingRecordDto recordDto = parkingRecordService.findById(recordId);
        if (recordDto == null) {
            throw new BadRequestException("停车记录不存在");
        }
        if (recordDto.getExitTime() == null) {
            throw new BadRequestException("车辆尚未出场，无法创建订单");
        }
        if (recordDto.getStatus() == 2) {
            throw new BadRequestException("该停车记录已完成支付");
        }

        // 2. 检查是否已存在订单
        OrderDto existingOrder = findByRecordId(recordId);
        if (existingOrder != null) {
            throw new BadRequestException("该停车记录已存在订单");
        }

        // 3. 计算费用
        BigDecimal fee = feeCalculationService.calculateFee(recordDto);

        // 4. 生成订单号
        String orderNo = generateOrderNo();

        // 5. 创建订单
        Long id = redisUtils.increment(ORDER_PREFIX + "id_gen");

        OrderDto orderDto = new OrderDto();
        orderDto.setId(id);
        orderDto.setOrderNo(orderNo);
        orderDto.setRecordId(recordId);
        orderDto.setPlateNumber(recordDto.getPlateNumber());
        orderDto.setTotalAmount(fee);
        orderDto.setPaidAmount(BigDecimal.ZERO);
        orderDto.setStatus(0); // 待支付
        orderDto.setStatusDesc("待支付");
        orderDto.setExpireTime(DateUtil.offsetMinute(new Date(), (int) PAY_EXPIRE_MINUTES));

        // 6. 保存订单
        saveToRedis(orderDto);
        addIdToList(id);
        redisUtils.set(ORDER_NO_INDEX + orderNo, id, EXPIRATION_TIME, TimeUnit.SECONDS);
        redisUtils.set(RECORD_ORDER_INDEX + recordId, id, EXPIRATION_TIME, TimeUnit.SECONDS);

        // 7. 更新停车记录状态为"已结算"
        parkingRecordService.updateStatus(recordId, 1);

        log.info("创建订单成功：{}，金额：{}", orderNo, fee);
        return orderDto;
    }

    @Override
    public Map<String, Object> payWithWxpay(Long orderId) {
        OrderDto orderDto = getFromRedis(orderId);
        if (orderDto == null) {
            throw new BadRequestException("订单不存在");
        }
        if (orderDto.getStatus() != 0) {
            throw new BadRequestException("订单状态不允许支付");
        }

        // 模拟调用微信支付API，生成支付二维码
        // 实际项目中需要调用微信支付SDK
        Map<String, Object> result = new HashMap<>();
        result.put("payType", "wxpay");
        result.put("orderNo", orderDto.getOrderNo());
        result.put("totalAmount", orderDto.getTotalAmount());
        result.put("qrCodeUrl", "weixin://wxpay/bizpayurl?pr=" + orderDto.getOrderNo()); // 模拟二维码链接
        result.put("expireTime", orderDto.getExpireTime());

        log.info("发起微信支付：订单号{}，金额{}", orderDto.getOrderNo(), orderDto.getTotalAmount());
        return result;
    }

    @Override
    public Map<String, Object> payWithAlipay(Long orderId) {
        OrderDto orderDto = getFromRedis(orderId);
        if (orderDto == null) {
            throw new BadRequestException("订单不存在");
        }
        if (orderDto.getStatus() != 0) {
            throw new BadRequestException("订单状态不允许支付");
        }

        // 模拟调用支付宝支付API，生成支付链接
        Map<String, Object> result = new HashMap<>();
        result.put("payType", "alipay");
        result.put("orderNo", orderDto.getOrderNo());
        result.put("totalAmount", orderDto.getTotalAmount());
        result.put("payUrl", "https://openapi.alipay.com/gateway.do?out_trade_no=" + orderDto.getOrderNo()); // 模拟支付链接
        result.put("expireTime", orderDto.getExpireTime());

        log.info("发起支付宝支付：订单号{}，金额{}", orderDto.getOrderNo(), orderDto.getTotalAmount());
        return result;
    }

    @Override
    public String handleWxpayNotify(HttpServletRequest request) {
        try {
            // 1. 读取微信回调参数
            // 实际项目中需要解析微信返回的XML数据
            String orderNo = request.getParameter("out_trade_no");
            String transactionId = request.getParameter("transaction_id");
            String resultCode = request.getParameter("result_code");

            if (StrUtil.isBlank(orderNo) || StrUtil.isBlank(transactionId)) {
                log.warn("微信回调参数不完整");
                return "FAIL";
            }

            // 2. 查询订单
            OrderDto orderDto = findByOrderNo(orderNo);
            if (orderDto == null) {
                log.warn("微信回调：订单不存在，orderNo={}", orderNo);
                return "FAIL";
            }

            // 3. 幂等性处理：如果订单已支付，直接返回成功
            if (orderDto.getStatus() == 1) {
                log.info("微信回调：订单已支付，忽略重复通知，orderNo={}", orderNo);
                return "SUCCESS";
            }

            // 4. 支付成功处理
            if ("SUCCESS".equals(resultCode)) {
                orderDto.setStatus(1);
                orderDto.setStatusDesc("已支付");
                orderDto.setPaidAmount(orderDto.getTotalAmount());
                orderDto.setPayChannel("wxpay");
                orderDto.setPayTime(new Date());
                saveToRedis(orderDto);

                // 5. 更新停车记录和车位状态
                completePayment(orderDto.getRecordId());

                log.info("微信支付成功：订单号{}，交易号{}", orderNo, transactionId);
                return "SUCCESS";
            }

            return "FAIL";
        } catch (Exception e) {
            log.error("微信回调处理异常", e);
            return "FAIL";
        }
    }

    @Override
    public String handleAlipayNotify(HttpServletRequest request) {
        try {
            // 1. 读取支付宝回调参数
            String orderNo = request.getParameter("out_trade_no");
            String tradeNo = request.getParameter("trade_no");
            String tradeStatus = request.getParameter("trade_status");

            if (StrUtil.isBlank(orderNo) || StrUtil.isBlank(tradeNo)) {
                log.warn("支付宝回调参数不完整");
                return "fail";
            }

            // 2. 查询订单
            OrderDto orderDto = findByOrderNo(orderNo);
            if (orderDto == null) {
                log.warn("支付宝回调：订单不存在，orderNo={}", orderNo);
                return "fail";
            }

            // 3. 幂等性处理
            if (orderDto.getStatus() == 1) {
                log.info("支付宝回调：订单已支付，忽略重复通知，orderNo={}", orderNo);
                return "success";
            }

            // 4. 支付成功处理
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                orderDto.setStatus(1);
                orderDto.setStatusDesc("已支付");
                orderDto.setPaidAmount(orderDto.getTotalAmount());
                orderDto.setPayChannel("alipay");
                orderDto.setPayTime(new Date());
                saveToRedis(orderDto);

                // 5. 更新停车记录和车位状态
                completePayment(orderDto.getRecordId());

                log.info("支付宝支付成功：订单号{}，交易号{}", orderNo, tradeNo);
                return "success";
            }

            return "fail";
        } catch (Exception e) {
            log.error("支付宝回调处理异常", e);
            return "fail";
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualPay(Long orderId) {
        OrderDto orderDto = getFromRedis(orderId);
        if (orderDto == null) {
            throw new BadRequestException("订单不存在");
        }
        if (orderDto.getStatus() != 0) {
            throw new BadRequestException("订单状态不允许手动支付");
        }

        orderDto.setStatus(1);
        orderDto.setStatusDesc("已支付");
        orderDto.setPaidAmount(orderDto.getTotalAmount());
        orderDto.setPayChannel("manual");
        orderDto.setPayTime(new Date());
        saveToRedis(orderDto);

        completePayment(orderDto.getRecordId());
        log.info("手动标记支付成功：订单号{}", orderDto.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeOrder(Long orderId) {
        OrderDto orderDto = getFromRedis(orderId);
        if (orderDto == null) {
            throw new BadRequestException("订单不存在");
        }
        if (orderDto.getStatus() != 0) {
            throw new BadRequestException("订单状态不允许关闭");
        }

        orderDto.setStatus(4);
        orderDto.setStatusDesc("已关闭");
        saveToRedis(orderDto);

        log.info("关闭订单成功：订单号{}", orderDto.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refund(Long orderId, String reason) {
        OrderDto orderDto = getFromRedis(orderId);
        if (orderDto == null) {
            throw new BadRequestException("订单不存在");
        }
        if (orderDto.getStatus() != 1) {
            throw new BadRequestException("仅已支付订单可以退款");
        }

        // 模拟调用微信/支付宝退款API
        // 实际项目中需要调用第三方退款接口

        orderDto.setStatus(2);
        orderDto.setStatusDesc("已退款");
        saveToRedis(orderDto);

        // 恢复停车记录和车位状态
        ParkingRecordDto recordDto = parkingRecordService.findById(orderDto.getRecordId());
        if (recordDto != null) {
            parkingRecordService.updateStatus(orderDto.getRecordId(), 1); // 恢复为已结算
            parkingLotService.updateStatus(recordDto.getLotId(), 0); // 车位恢复为空闲
        }

        log.info("退款成功：订单号{}，原因：{}", orderDto.getOrderNo(), reason);
    }

    @Override
    public Map<String, Object> queryRefund(Long orderId) {
        OrderDto orderDto = getFromRedis(orderId);
        if (orderDto == null) {
            throw new BadRequestException("订单不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", orderDto.getOrderNo());
        result.put("status", orderDto.getStatus());
        result.put("statusDesc", orderDto.getStatusDesc());
        result.put("refundAmount", orderDto.getPaidAmount());

        return result;
    }

    @Override
    public void export(HttpServletResponse response, OrderQueryCriteria criteria) throws IOException {
        List<OrderDto> list = queryAll(criteria);
        List<Map<String, Object>> dataList = new ArrayList<>();

        for (OrderDto dto : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("订单号", dto.getOrderNo());
            row.put("车牌号", dto.getPlateNumber());
            row.put("订单金额", dto.getTotalAmount());
            row.put("实付金额", dto.getPaidAmount());
            row.put("支付渠道", dto.getPayChannelDesc());
            row.put("订单状态", dto.getStatusDesc());
            row.put("支付时间", dto.getPayTime());
            row.put("创建时间", dto.getCreateTime());
            dataList.add(row);
        }

        FileUtil.downloadExcel(dataList, response);
    }

    @Override
    public Map<String, Object> getStatistics() {
        List<OrderDto> allOrders = queryAll(new OrderQueryCriteria());

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", allOrders.size());

        // 统计各状态订单数量
        Map<Integer, Long> statusCount = allOrders.stream()
                .collect(Collectors.groupingBy(OrderDto::getStatus, Collectors.counting()));
        stats.put("statusCount", statusCount);

        // 统计总金额
        BigDecimal totalAmount = allOrders.stream()
                .filter(o -> o.getStatus() == 1)
                .map(OrderDto::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalAmount", totalAmount);

        // 今日订单数
        long todayCount = allOrders.stream()
                .filter(o -> DateUtil.isSameDay(o.getCreateTime(), new Date()))
                .count();
        stats.put("todayCount", todayCount);

        return stats;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 生成订单号
     * 格式：PK + yyyyMMddHHmmss + 6位随机数
     */
    private String generateOrderNo() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomStr = String.format("%06d", new Random().nextInt(999999));
        return "PK" + dateStr + randomStr;
    }

    /**
     * 支付完成后更新停车记录和车位状态
     */
    private void completePayment(Long recordId) {
        // 更新停车记录状态为"已支付"
        parkingRecordService.updateStatus(recordId, 2);

        // 获取停车记录，释放车位
        ParkingRecordDto recordDto = parkingRecordService.findById(recordId);
        if (recordDto != null && recordDto.getLotId() != null) {
            parkingLotService.updateStatus(recordDto.getLotId(), 0); // 车位恢复为空闲
        }
    }

    /**
     * 从 Redis 中获取所有订单 ID 列表
     */
    private List<Long> getIdsFromRedis() {
        Object obj = redisUtils.get(ORDER_IDS_KEY);
        if (obj == null) {
            return new ArrayList<>();
        }
        return JSON.parseObject(obj.toString(), new TypeReference<List<Long>>() {});
    }

    /**
     * 将 ID 添加到 Redis 的 ID 列表中
     */
    private void addIdToList(Long id) {
        List<Long> ids = getIdsFromRedis();
        if (!ids.contains(id)) {
            ids.add(id);
            redisUtils.set(ORDER_IDS_KEY, ids, EXPIRATION_TIME, TimeUnit.SECONDS);
        }
    }

    /**
     * 从 Redis 中获取单个订单 DTO
     */
    private OrderDto getFromRedis(Long id) {
        if (id == null) {
            return null;
        }
        return redisUtils.get(ORDER_PREFIX + id, OrderDto.class);
    }

    /**
     * 将订单 DTO 保存到 Redis
     */
    private void saveToRedis(OrderDto dto) {
        if (dto == null || dto.getId() == null) {
            return;
        }
        redisUtils.set(ORDER_PREFIX + dto.getId(), dto, EXPIRATION_TIME, TimeUnit.SECONDS);
    }

    /**
     * 判断 DTO 是否匹配查询条件
     */
    private boolean matchCriteria(OrderDto dto, OrderQueryCriteria criteria) {
        if (criteria == null) {
            return true;
        }

        if (StrUtil.isNotBlank(criteria.getOrderNo())) {
            if (!dto.getOrderNo().contains(criteria.getOrderNo())) {
                return false;
            }
        }

        if (criteria.getRecordId() != null) {
            if (!criteria.getRecordId().equals(dto.getRecordId())) {
                return false;
            }
        }

        if (criteria.getStatus() != null) {
            if (!criteria.getStatus().equals(dto.getStatus())) {
                return false;
            }
        }

        if (StrUtil.isNotBlank(criteria.getPayChannel())) {
            if (!criteria.getPayChannel().equals(dto.getPayChannel())) {
                return false;
            }
        }

        return true;
    }
}