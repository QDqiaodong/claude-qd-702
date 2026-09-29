package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.OrderDeviceLink;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.StandardDeviceDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.OrderDeviceLinkRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

/** 模块五：标准器（溯源与证书有效期倒计时、占用情况、档案/证书修订）。 */
@Service
public class StandardDeviceService {

    private final StandardDeviceRepository deviceRepository;
    private final OrderDeviceLinkRepository linkRepository;
    private final CalibrationOrderRepository orderRepository;

    public StandardDeviceService(StandardDeviceRepository deviceRepository,
                                 OrderDeviceLinkRepository linkRepository,
                                 CalibrationOrderRepository orderRepository) {
        this.deviceRepository = deviceRepository;
        this.linkRepository = linkRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.DeviceView> list() {
        Map<Long, String> occupancy = activeOccupancy();
        List<Views.DeviceView> list = new ArrayList<>();
        for (StandardDevice device : deviceRepository.findAllByOrderByCertValidUntilAsc()) {
            list.add(toView(device, occupancy.get(device.getId())));
        }
        return list;
    }

    /** deviceId -> 占用方委托单号（活动行），多出来的异常活动行也如实带出第一笔 */
    private Map<Long, String> activeOccupancy() {
        Map<Long, Long> holder = new HashMap<>();
        for (OrderDeviceLink link : linkRepository.findAllByReleasedAtIsNull()) {
            holder.putIfAbsent(link.getDeviceId(), link.getOrderId());
        }
        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<Long, Long> entry : holder.entrySet()) {
            orderRepository.findById(entry.getValue())
                    .ifPresent(o -> result.put(entry.getKey(), o.getOrderNo()));
        }
        return result;
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
        return toView(device, null);
    }

    /** 送上级机构溯源 / 停用 / 回用在役。被占用中也允许停用——占用它的委托下一次流转会被拦下 */
    @Transactional
    public Views.DeviceView setStatus(Long id, String status) {
        StandardDevice device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("标准器不存在：id=" + id));
        if (!MetroDict.isValidDeviceStatus(status)) {
            throw new BizException("标准器状态不合法，应为 在用 / 停用 / 送检");
        }
        device.setStatus(status);
        deviceRepository.save(device);
        return toView(device, activeOccupancy().get(id));
    }

    /**
     * 修订档案/证书（如证书被修订为更早到期日）。
     * 字段为 null 表示不改；空串表示清空。改完不影响挂接关系本身，
     * 但所有占用委托详情/队列立即按新数据重算不可放行原因，无法靠刷新绕过。
     */
    @Transactional
    public Views.DeviceView update(Long id, StandardDeviceDtos.DeviceUpdateReq req) {
        StandardDevice device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("标准器不存在：id=" + id));
        if (req.getName() != null) {
            if (req.getName().isBlank()) {
                throw new BizException("标准器名称不能为空");
            }
            device.setName(req.getName().trim());
        }
        if (req.getModel() != null) {
            device.setModel(blankToNull(req.getModel()));
        }
        if (req.getMeasureRange() != null) {
            device.setMeasureRange(blankToNull(req.getMeasureRange()));
        }
        if (req.getTraceOrg() != null) {
            device.setTraceOrg(blankToNull(req.getTraceOrg()));
        }
        if (req.getLastTraceDate() != null) {
            device.setLastTraceDate(parseDate(req.getLastTraceDate()));
        }
        if (req.getCertValidUntil() != null) {
            device.setCertValidUntil(parseDate(req.getCertValidUntil()));
        }
        if (req.getStatus() != null) {
            String status = req.getStatus().trim();
            if (!MetroDict.isValidDeviceStatus(status)) {
                throw new BizException("标准器状态不合法，应为 在用 / 停用 / 送检");
            }
            device.setStatus(status);
        }
        if (req.getRemark() != null) {
            device.setRemark(blankToNull(req.getRemark()));
        }
        deviceRepository.save(device);
        return toView(device, activeOccupancy().get(id));
    }

    private Views.DeviceView toView(StandardDevice device, String occupiedByOrderNo) {
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
                device.getRemark(),
                occupiedByOrderNo != null,
                occupiedByOrderNo);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDate.parse(text.trim());
    }
}
