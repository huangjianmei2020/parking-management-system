package me.zhengjie.modules.parking.config;

import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.parking.dto.AppointmentDto;
import me.zhengjie.modules.parking.service.ParkingLotService;
import me.zhengjie.utils.RedisUtils;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RedisKeyExpirationListener implements CommandLineRunner {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisUtils redisUtils;
    private final ParkingLotService parkingLotService;

    private static final String APPOINT_TIMEOUT_PREFIX = "parking:appoint:timeout:";
    private static final String APPOINT_PREFIX = "parking:appoint:";
    private static final String APPOINT_PLATE_INDEX = "parking:appoint:plate:";
    private static final long EXPIRATION_TIME = 30 * 24 * 60 * 60;

    public RedisKeyExpirationListener(StringRedisTemplate stringRedisTemplate,
                                      RedisUtils redisUtils,
                                      ParkingLotService parkingLotService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisUtils = redisUtils;
        this.parkingLotService = parkingLotService;
    }

    @Override
    public void run(String... args) {
        // 在新线程中启动 Redis 过期事件监听，避免阻塞主线程
        new Thread(() -> {
            try {
                stringRedisTemplate.getConnectionFactory().getConnection()
                        .subscribe(new MessageListener() {
                            @Override
                            public void onMessage(Message message, byte[] pattern) {
                                String expiredKey = new String(message.getBody());

                                // 只处理预约超时的 Key
                                if (expiredKey.startsWith(APPOINT_TIMEOUT_PREFIX)) {
                                    String appointIdStr = expiredKey.replace(APPOINT_TIMEOUT_PREFIX, "");
                                    Long appointId = Long.parseLong(appointIdStr);

                                    try {
                                        AppointmentDto dto = redisUtils.get(APPOINT_PREFIX + appointId, AppointmentDto.class);

                                        if (dto != null && dto.getStatus() == 0) {
                                            // 更新预约状态为已过期
                                            dto.setStatus(3);
                                            dto.setStatusDesc("已过期");
                                            redisUtils.set(APPOINT_PREFIX + appointId, dto, EXPIRATION_TIME, TimeUnit.SECONDS);

                                            // 释放车位
                                            if (dto.getLotId() != null) {
                                                parkingLotService.updateStatus(dto.getLotId(), 0);
                                            }

                                            // 清除车牌预约索引
                                            redisUtils.del(APPOINT_PLATE_INDEX + dto.getPlateNumber());

                                            log.info("【Redis过期通知】预约超时自动释放：预约ID{}，车牌号{}，车位ID{}",
                                                    appointId, dto.getPlateNumber(), dto.getLotId());
                                        }
                                    } catch (Exception e) {
                                        log.error("【Redis过期通知】预约超时释放失败：预约ID{}", appointId, e);
                                    }
                                }
                            }
                        }, "__keyevent@0__:expired".getBytes());
            } catch (Exception e) {
                log.error("Redis 过期监听器启动失败", e);
            }
        }).start();

        log.info("Redis 过期监听器启动成功，等待预约超时事件...");
    }
}