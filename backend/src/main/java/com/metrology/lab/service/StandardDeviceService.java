package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.StandardDeviceDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.StandardDeviceRepository;

/** 模块五：标准器（溯源与证书有效期倒计时）。 */
@Service
public class StandardDeviceService {

    private final StandardDeviceRepository deviceRepository;

    public StandardDeviceService(StandardDeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.DeviceView> list() {
        List<Views.DeviceView> list = new ArrayList<>();
        for (StandardDevice device : deviceRepository.findAllByOrderByCertValidUntilAsc()) {
            list.add(toView(device));
        }
        return list;
    }

    @Transactional
    public Views.DeviceView create(StandardDeviceDtos.DeviceSaveReq req) {
        if (deviceRepository.existsByDeviceNo(req.getDeviceNo())) {
            throw new BizException("标准器编号已存在：" + req.getDeviceNo());
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "IN_USE" : req.getStatus().trim();
        if (!MetroDict.isValidDeviceStatus(status)) {
            throw new BizException("标准器状态不合法，应为 在用 / 停用 / 送检");
        }
        StandardDevice device = new StandardDevice();
        device.setDeviceNo(req.getDeviceNo());
        device.setName(req.getName());
        device.setModel(req.getModel());
        device.setMeasureRange(req.getMeasureRange());
        device.setTraceOrg(req.getTraceOrg());
        device.setLastTraceDate(parseDate(req.getLastTraceDate()));
        device.setCertValidUntil(parseDate(req.getCertValidUntil()));
        device.setStatus(status);
        device.setRemark(req.getRemark());
        deviceRepository.save(device);
        return toView(device);
    }

    /** 送上级机构溯源：状态置为「送检」 */
    @Transactional
    public Views.DeviceView setStatus(Long id, String status) {
        StandardDevice device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("标准器不存在：id=" + id));
        if (!MetroDict.isValidDeviceStatus(status)) {
            throw new BizException("标准器状态不合法，应为 在用 / 停用 / 送检");
        }
        device.setStatus(status);
        deviceRepository.save(device);
        return toView(device);
    }

    private Views.DeviceView toView(StandardDevice device) {
        LocalDate validUntil = device.getCertValidUntil();
        Integer remainDays = validUntil == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), validUntil);
        boolean expired = remainDays != null && remainDays < 0;
        boolean expiring = remainDays != null && remainDays >= 0
                && remainDays <= MetroDict.DEVICE_VALID_WARN_DAYS;
        return new Views.DeviceView(
                device.getId(),
                device.getDeviceNo(),
                device.getName(),
                device.getModel(),
                device.getMeasureRange(),
                device.getTraceOrg(),
                device.getLastTraceDate(),
                validUntil,
                device.getStatus(),
                MetroDict.deviceStatusName(device.getStatus()),
                remainDays,
                expired,
                expiring,
                device.getRemark());
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDate.parse(text.trim());
    }
}
