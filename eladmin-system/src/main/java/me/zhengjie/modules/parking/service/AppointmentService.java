package me.zhengjie.modules.parking.service;

import me.zhengjie.modules.parking.dto.AppointConfigDto;
import me.zhengjie.modules.parking.dto.AppointmentDto;
import me.zhengjie.modules.parking.dto.AppointmentQueryCriteria;
import me.zhengjie.modules.parking.dto.ParkingRecordDto;
import me.zhengjie.modules.parking.controller.AppointmentController.*;
import me.zhengjie.utils.PageResult;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Map;

public interface AppointmentService {

    AppointmentDto createAppointment(String plateNumber, Integer vehicleType, Long lotId);

    void cancelAppointment(Long id);

    PageResult<AppointmentDto> queryAll(AppointmentQueryCriteria criteria, Pageable pageable);

    List<AppointmentDto> queryAll(AppointmentQueryCriteria criteria);

    List<AppointmentDto> getCurrentAppointments();

    ParkingRecordDto arriveAndEntry(Long id);

    void updateConfig(AppointConfigRequest request);

    AppointConfigDto getConfig();

    Map<String, Object> getStatistics();
}