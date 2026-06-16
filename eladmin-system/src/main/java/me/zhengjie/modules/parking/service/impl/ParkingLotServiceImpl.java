package me.zhengjie.modules.parking.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.parking.domain.ParkingLot;
import me.zhengjie.modules.parking.dto.ParkingLotDto;
import me.zhengjie.modules.parking.dto.ParkingLotQueryCriteria;
import me.zhengjie.modules.parking.service.ParkingLotService;
import me.zhengjie.modules.parking.dto.ParkingLotDto;
import me.zhengjie.modules.parking.dto.ParkingLotQueryCriteria;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.RedisUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 车位管理 Service 实现类（Redis 版）
 * 仿照 OnlineUserService 的风格，使用 Redis 作为数据存储和缓存
 */
@Service(value = "ParkingLotService")
@Slf4j
@AllArgsConstructor
public class ParkingLotServiceImpl implements ParkingLotService {
     private final RedisUtils redisUtils;
     /** Redis 中车位数据的 key 前缀 */
     private static final String PARKING_LOT_PREFIX = "parking:lot:";
     /** 所有车位 ID 列表的 key */
     private static final String PARKING_LOT_IDS_KEY = "parking:lot:ids";
     /** 车位编号索引的 key 前缀 */
     private static final String PARKING_LOT_CODE_INDEX = "parking:lot:code:";
     /** 缓存过期时间（秒），7天 */
     private static final long EXPIRATION_TIME = 7 * 24 * 60 * 60;

     @Override
     public PageResult<ParkingLotDto> queryAll(ParkingLotQueryCriteria criteria, Pageable pageable) {
          List<ParkingLotDto> allList = queryAll(criteria);
          return PageUtil.toPage(
                  PageUtil.paging(pageable.getPageNumber(), pageable.getPageSize(), allList),
                  allList.size()
          );
     }

     @Override
     public Long findAvailableLotId(Integer vehicleType) {
          // 获取所有车位ID列表
          List<Long> ids = getIdsFromRedis();
          if (CollectionUtil.isEmpty(ids)) {
               return null;
          }

          // 遍历查找空闲车位
          for (Long id : ids) {
               ParkingLotDto dto = getFromRedis(id);
               if (dto != null && dto.getStatus() == 0) {
                    if (vehicleType != null) {
                         // 小型车可以停小型车位和新能源车位
                         // 大型车只能停大型车位
                         // 新能源车可以停新能源车位和小型车位
                         if (isCompatible(vehicleType, dto.getType())) {
                              return id;
                         }
                    } else {
                         // 未指定车辆类型，返回第一个空闲车位
                         return id;
                    }
               }
          }

          return null; // 没有空闲车位
     }

     /**
      * 判断车辆类型是否与车位类型兼容
      */
     private boolean isCompatible(Integer vehicleType, Integer lotType) {
          if (vehicleType == null || lotType == null) {
               return true;
          }

          switch (vehicleType) {
               case 1: // 小型车：可以停小型车位和新能源车位
                    return lotType == 1 || lotType == 3;
               case 2: // 大型车：只能停大型车位
                    return lotType == 2;
               case 3: // 新能源车：可以停新能源车位和小型车位
                    return lotType == 3 || lotType == 1;
               default:
                    return false;
          }
     }
     @Override
     public Integer checkAndOccupy(Long lotId) {
          ParkingLotDto dto = getFromRedis(lotId);
          if (dto == null) {
               throw new BadRequestException("车位不存在，ID：" + lotId);
          }
          return dto.getStatus();
     }

     @Override
     public List<ParkingLotDto> queryAll(ParkingLotQueryCriteria criteria) {
          // 获取所有车位 ID 列表
          List<Long> ids = getIdsFromRedis();
          if (CollectionUtil.isEmpty(ids)) {
               return new ArrayList<>();
          }

          List<ParkingLotDto> resultList = new ArrayList<>();
          for (Long id : ids) {
               ParkingLotDto dto = getFromRedis(id);
               if (dto != null) {
                    // 应用查询条件过滤
                    if (matchCriteria(dto, criteria)) {
                         resultList.add(dto);
                    }
               }
          }

          // 排序（按 ID 降序，最新的在前）
          resultList.sort((o1, o2) -> o2.getId().compareTo(o1.getId()));
          return resultList;
     }


     @Override
     public ParkingLotDto findById(Long id) {
          return getFromRedis(id);
     }

     @Override
     public ParkingLot findByIdentifier(Long id) {
          ParkingLotDto dto = getFromRedis(id);
          if (dto == null) {
               return null;
          }
          return convertToEntity(dto);
     }

     @Override
     public ParkingLot findByCode(String code) {
          if (StrUtil.isBlank(code)) {
               return null;
          }
          // 先从编号索引中查找 ID
          Long id = redisUtils.get(PARKING_LOT_CODE_INDEX + code, Long.class);
          if (id == null) {
               return null;
          }
          ParkingLotDto dto = getFromRedis(id);
          return dto == null ? null : convertToEntity(dto);
     }

     @Override
     public ParkingLot create(ParkingLot resources) {
          // 校验车位编号唯一性
          ParkingLot existing = findByCode(resources.getCode());
          if (existing != null) {
               throw new EntityExistException(ParkingLot.class, "code", resources.getCode());
          }

          // 生成 ID（使用 Redis 自增）
          Long id = redisUtils.increment(PARKING_LOT_PREFIX + "id_gen");
          System.out.println("车位编号为:"+resources.getCode()+"的车位对应的id为:"+id);
          resources.setId(id);
          resources.setStatus(0); // 新车位默认空闲

          // 转换为 DTO 并保存到 Redis
          ParkingLotDto dto = convertToDto(resources);
          saveToRedis(dto);

          // 添加到 ID 列表
          addIdToList(id);

          // 建立编号索引
          redisUtils.set(PARKING_LOT_CODE_INDEX + resources.getCode(), id, EXPIRATION_TIME, TimeUnit.SECONDS);

          log.info("新增车位成功：{}", resources.getCode());
          return resources;
     }

     @Override
     public void update(ParkingLot resources) {
          ParkingLotDto dto = getFromRedis(resources.getId());
          if (dto == null) {
               throw new BadRequestException("车位不存在，ID：" + resources.getId());
          }

          // 校验车位编号唯一性（排除自身）
          if (!resources.getCode().equals(dto.getCode())) {
               ParkingLot existing = findByCode(resources.getCode());
               if (existing != null && !existing.getId().equals(resources.getId())) {
                    throw new EntityExistException(ParkingLot.class, "code", resources.getCode());
               }
               // 删除旧的编号索引
               redisUtils.del(PARKING_LOT_CODE_INDEX + dto.getCode());
               // 建立新的编号索引
               redisUtils.set(PARKING_LOT_CODE_INDEX + resources.getCode(), resources.getId(), EXPIRATION_TIME, TimeUnit.SECONDS);
          }

          // 更新字段
          dto.setCode(resources.getCode());
          dto.setFloor(resources.getFloor());
          dto.setType(resources.getType());
          // status 不由更新接口修改，由入场/出场业务逻辑修改
          //dto.setStatus(resources.getStatus());
          //dto.setStatusDesc(convertStatus(resources.getStatus()));

          // 保存回 Redis
          saveToRedis(dto);
          log.info("更新车位成功：{}", resources.getCode());
     }

     @Override
     public void delete(Set<Long> ids) {
          for (Long id : ids) {
               ParkingLotDto dto = getFromRedis(id);
               if (dto == null) {
                    throw new BadRequestException("车位不存在，ID：" + id);
               }

               // 仅允许删除空闲状态的车位
               if (dto.getStatus() != 0) {
                    throw new BadRequestException("车位「" + dto.getCode() + "」当前状态不允许删除");
               }

               // 删除编号索引
               redisUtils.del(PARKING_LOT_CODE_INDEX + dto.getCode());

               // 从 ID 列表中移除
               removeIdFromList(id);

               // 删除车位数据
               redisUtils.del(PARKING_LOT_PREFIX + id);

               log.info("删除车位成功：{}", dto.getCode());
          }
     }

     @Override
     public void importExcel(MultipartFile file) throws Exception {
          // 解析 Excel 文件
          List<Map<String, Object>> list = FileUtil.importExcel(file);
          if (CollectionUtil.isEmpty(list)) {
               throw new BadRequestException("导入数据为空");
          }

          List<ParkingLot> parkingLotList = new ArrayList<>();
          for (Map<String, Object> map : list) {
               ParkingLot parkingLot = new ParkingLot();

               String code = Objects.toString(map.get("车位编号"), "");
               if (code.isEmpty()) {
                    continue; // 跳过空行
               }

               // 检查编号是否已存在
               if (findByCode(code) != null) {
                    throw new BadRequestException("车位编号「" + code + "」已存在");
               }

               parkingLot.setCode(code);
               parkingLot.setFloor(Objects.toString(map.get("所属楼层"), ""));

               // 处理车位类型
               String typeStr = Objects.toString(map.get("车位类型"), "小型车");
               switch (typeStr) {
                    case "小型车":
                         parkingLot.setType(1);
                         break;
                    case "大型车":
                         parkingLot.setType(2);
                         break;
                    case "新能源":
                         parkingLot.setType(3);
                         break;
                    default:
                         parkingLot.setType(1);
               }

               parkingLot.setStatus(0); // 导入的车位默认空闲
               parkingLotList.add(parkingLot);
          }

          if (CollectionUtil.isEmpty(parkingLotList)) {
               throw new BadRequestException("没有有效的车位数据可导入");
          }

          // 批量保存
          for (ParkingLot parkingLot : parkingLotList) {
               create(parkingLot);
          }

          log.info("批量导入车位成功，共导入 {} 条", parkingLotList.size());
     }

     @Override
     public void export(HttpServletResponse response, ParkingLotQueryCriteria criteria) throws IOException {
          List<ParkingLotDto> list = queryAll(criteria);
          List<Map<String, Object>> dataList = new ArrayList<>();

          for (ParkingLotDto dto : list) {
               Map<String, Object> row = new LinkedHashMap<>();
               row.put("车位编号", dto.getCode());
               row.put("所属楼层", dto.getFloor());
               row.put("车位类型", convertType(dto.getType()));
               row.put("状态", convertStatus(dto.getStatus()));
               dataList.add(row);
          }

          FileUtil.downloadExcel(dataList, response);
     }

     @Override
     public Map<Integer, Long> getStatusCount() {
          Map<Integer, Long> countMap = new HashMap<>();
          countMap.put(0, 0L); // 空闲
          countMap.put(1, 0L); // 已占用
          countMap.put(2, 0L); // 预留
          countMap.put(3, 0L); // 维修中

          List<Long> ids = getIdsFromRedis();
          if (CollectionUtil.isEmpty(ids)) {
               return countMap;
          }

          for (Long id : ids) {
               ParkingLotDto dto = getFromRedis(id);
               if (dto != null) {
                    Long count = countMap.getOrDefault(dto.getStatus(), 0L);
                    countMap.put(dto.getStatus(), count + 1);
               }
          }

          return countMap;
     }

     @Override
     public void updateStatus(Long id, Integer status) {
          ParkingLotDto dto = getFromRedis(id);
          if (dto == null) {
               throw new BadRequestException("车位不存在，ID：" + id);
          }
          dto.setStatus(status);
          String statusDesc = convertStatus(status);
          dto.setStatusDesc(statusDesc);
          saveToRedis(dto);
          log.info("更新车位状态成功：{} -> {}", dto.getCode(), convertStatus(status));
     }

     // ==================== 私有辅助方法 ====================

     /**
      * 从 Redis 中获取所有车位 ID 列表
      */
     private List<Long> getIdsFromRedis() {
          Object obj = redisUtils.get(PARKING_LOT_IDS_KEY);
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
               redisUtils.set(PARKING_LOT_IDS_KEY, ids, EXPIRATION_TIME, TimeUnit.SECONDS);
          }
     }

     /**
      * 从 Redis 的 ID 列表中移除指定 ID
      */
     private void removeIdFromList(Long id) {
          List<Long> ids = getIdsFromRedis();
          ids.remove(id);
          redisUtils.set(PARKING_LOT_IDS_KEY, ids, EXPIRATION_TIME, TimeUnit.SECONDS);
     }

     /**
      * 从 Redis 中获取单个车位 DTO
      */
     private ParkingLotDto getFromRedis(Long id) {
          if (id == null) {
               return null;
          }
          return redisUtils.get(PARKING_LOT_PREFIX + id, ParkingLotDto.class);
     }

     /**
      * 将车位 DTO 保存到 Redis
      */
     private void saveToRedis(ParkingLotDto dto) {
          if (dto == null || dto.getId() == null) {
               return;
          }
          redisUtils.set(PARKING_LOT_PREFIX + dto.getId(), dto, EXPIRATION_TIME, TimeUnit.SECONDS);
     }

     /**
      * 将 Entity 转换为 DTO
      */
     private ParkingLotDto convertToDto(ParkingLot entity) {
          if (entity == null) {
               return null;
          }
          ParkingLotDto dto = new ParkingLotDto();
          dto.setId(entity.getId());
          dto.setCode(entity.getCode());
          dto.setFloor(entity.getFloor());
          dto.setType(entity.getType());
          dto.setTypeDesc(convertType(entity.getType()));
          dto.setStatus(entity.getStatus());
          dto.setStatusDesc(convertStatus(entity.getStatus()));
          return dto;
     }

     /**
      * 将 DTO 转换为 Entity
      */
     private ParkingLot convertToEntity(ParkingLotDto dto) {
          if (dto == null) {
               return null;
          }
          ParkingLot entity = new ParkingLot();
          entity.setId(dto.getId());
          entity.setCode(dto.getCode());
          entity.setFloor(dto.getFloor());
          entity.setType(dto.getType());
          entity.setStatus(dto.getStatus());
          return entity;
     }

     /**
      * 判断 DTO 是否匹配查询条件
      */
     private boolean matchCriteria(ParkingLotDto dto, ParkingLotQueryCriteria criteria) {
          if (criteria == null) {
               return true;
          }

          // 车位编号模糊匹配
          if (StrUtil.isNotBlank(criteria.getCode())) {
               if (!dto.getCode().contains(criteria.getCode())) {
                    return false;
               }
          }

          // 楼层精确匹配
          if (StrUtil.isNotBlank(criteria.getFloor())) {
               if (!criteria.getFloor().equals(dto.getFloor())) {
                    return false;
               }
          }

          // 车位类型精确匹配
          if (criteria.getType() != null) {
               if (!criteria.getType().equals(dto.getType())) {
                    return false;
               }
          }

          // 状态精确匹配
          if (criteria.getStatus() != null) {
               if (!criteria.getStatus().equals(dto.getStatus())) {
                    return false;
               }
          }

          return true;
     }

     /**
      * 将车位类型数字转为中文描述
      */
     private String convertType(Integer type) {
          if (type == null) return "";
          switch (type) {
               case 1: return "小型车";
               case 2: return "大型车";
               case 3: return "新能源";
               default: return "未知";
          }
     }

     /**
      * 将状态数字转为中文描述
      */
     private String convertStatus(Integer status) {
          if (status == null) return "";
          switch (status) {
               case 0: return "空闲";
               case 1: return "已占用";
               case 2: return "预留";
               case 3: return "维修中";
               default: return "未知";
          }
     }
}