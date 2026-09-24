package com.metrology.lab.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.CalibrationOrder;
import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

/**
 * 模块四：检定委托与流转单。
 *
 * <p>状态沿 {@link MetroDict#FLOW_ORDER} 推进：
 * 收样 RECEIVED → 检定中 CALIBRATING → 核验 VERIFYING → 出证 CERTIFYING → 已发放 ISSUED。
 *
 * <p>建单与详情都从<b>器具编号</b>出发 —— 定位器具走 {@link InstrumentService#loadByNaturalId(String)}
 * 的 {@code @NaturalId} 自然主键加载，不在本类里另开派生查询。
 */
@Service
public class CalibrationOrderService {

    private final CalibrationOrderRepository orderRepository;
    private final InstrumentService instrumentService;
    private final InstrumentRepository instrumentRepository;
    private final ClientRepository clientRepository;
    private final ProcedureRepository procedureRepository;
    private final StandardDeviceRepository deviceRepository;

    public CalibrationOrderService(CalibrationOrderRepository orderRepository,
                                   InstrumentService instrumentService,
                                   InstrumentRepository instrumentRepository,
                                   ClientRepository clientRepository,
                                   ProcedureRepository procedureRepository,
                                   StandardDeviceRepository deviceRepository) {
        this.orderRepository = orderRepository;
        this.instrumentService = instrumentService;
        this.instrumentRepository = instrumentRepository;
        this.clientRepository = clientRepository;
        this.procedureRepository = procedureRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.OrderView> list(String status, Long clientId) {
        List<CalibrationOrder> orders;
        if (status != null && !status.isBlank()) {
            orders = orderRepository.findAllByStatusOrderByReceiveDateAsc(status.trim());
        } else if (clientId != null) {
            orders = orderRepository.findAllByClientIdOrderByReceiveDateDesc(clientId);
        } else {
            orders = orderRepository.findAllByOrderByReceiveDateDescIdAsc();
        }
        ViewContext context = context();
        List<Views.OrderView> list = new ArrayList<>();
        for (CalibrationOrder order : orders) {
            list.add(toView(order, context));
        }
        return list;
    }

    /** 左侧常驻「待办队列」：按流转状态分组 */
    @Transactional(readOnly = true)
    public List<Views.QueueGroupView> queue() {
        ViewContext context = context();
        LocalDate today = LocalDate.now();
        List<Views.QueueGroupView> groups = new ArrayList<>();
        for (String status : MetroDict.FLOW_ORDER) {
            List<Views.QueueItemView> items = new ArrayList<>();
            for (CalibrationOrder order : orderRepository.findAllByStatusOrderByReceiveDateAsc(status)) {
                Integer waiting = order.getReceiveDate() == null ? null
                        : (int) ChronoUnit.DAYS.between(order.getReceiveDate(), today);
                items.add(new Views.QueueItemView(
                        order.getId(),
                        order.getOrderNo(),
                        order.getInstNo(),
                        context.instrumentName.getOrDefault(order.getInstrumentId(), order.getInstNo()),
                        context.clientName.getOrDefault(order.getClientId(), ""),
                        order.getReceiveDate(),
                        waiting));
            }
            groups.add(new Views.QueueGroupView(status, MetroDict.flowStatusName(status), items.size(), items));
        }
        return groups;
    }

    /** 顶部概览计数器 */
    @Transactional(readOnly = true)
    public Views.OverviewView overview() {
        LocalDate today = LocalDate.now();
        long instrumentTotal = 0;
        long dueSoon = 0;
        long overdue = 0;
        for (Instrument instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            instrumentTotal++;
            LocalDate due = instrument.getNextDueDate();
            if (due != null) {
                long days = ChronoUnit.DAYS.between(today, due);
                if (days < 0) {
                    overdue++;
                } else if (days <= MetroDict.INSTRUMENT_DUE_WARN_DAYS) {
                    dueSoon++;
                }
            }
        }
        long orderTotal = 0;
        long orderOpen = 0;
        BigDecimal monthFee = BigDecimal.ZERO;
        int thisMonth = today.getMonthValue();
        for (CalibrationOrder order : orderRepository.findAllByOrderByReceiveDateDescIdAsc()) {
            orderTotal++;
            if (!"ISSUED".equals(order.getStatus())) {
                orderOpen++;
            }
            if (order.getReceiveDate() != null && order.getReceiveDate().getMonthValue() == thisMonth
                    && order.getFee() != null) {
                monthFee = monthFee.add(order.getFee());
            }
        }
        long deviceExpiring = 0;
        for (var device : deviceRepository.findAllByOrderByCertValidUntilAsc()) {
            if (device.getCertValidUntil() != null) {
                long days = ChronoUnit.DAYS.between(today, device.getCertValidUntil());
                if (days <= MetroDict.DEVICE_VALID_WARN_DAYS) {
                    deviceExpiring++;
                }
            }
        }
        return new Views.OverviewView(instrumentTotal, dueSoon, overdue, orderTotal, orderOpen,
                deviceExpiring, monthFee);
    }

    @Transactional(readOnly = true)
    public Views.OrderView detail(Long id) {
        CalibrationOrder order = require(id);
        return toView(order, context());
    }

    /** 【自然主键】按器具编号建委托单 */
    @Transactional
    public Views.OrderView create(CalibrationOrderDtos.OrderSaveReq req) {
        if (orderRepository.existsByOrderNo(req.getOrderNo())) {
            throw new BizException("委托单号已存在：" + req.getOrderNo());
        }
        Instrument instrument = instrumentService.loadByNaturalId(req.getInstNo());
        if ("SCRAPPED".equals(instrument.getStatus())) {
            throw new BizException("器具 " + instrument.getInstNo() + " 已报废，不能受理委托");
        }
        if (instrument.getClientId() == null) {
            throw new BizException("器具 " + instrument.getInstNo() + " 尚未登记所属送检客户");
        }
        var procedure = procedureRepository.findByProcNo(req.getProcNo())
                .orElseThrow(() -> new BizException("规程不存在：" + req.getProcNo()));
        if (req.getConclusion() != null && !req.getConclusion().isBlank()
                && !MetroDict.isValidConclusion(req.getConclusion())) {
            throw new BizException("检定结论只能是 PASS / LIMITED / FAIL");
        }
        CalibrationOrder order = new CalibrationOrder();
        order.setOrderNo(req.getOrderNo().trim());
        order.setClientId(instrument.getClientId());
        order.setInstrumentId(instrument.getId());
        order.setInstNo(instrument.getInstNo());
        order.setProcedureId(procedure.getId());
        order.setReceiveDate(parseDate(req.getReceiveDate(), LocalDate.now()));
        order.setVerifier(blankToNull(req.getVerifier()));
        order.setChecker(blankToNull(req.getChecker()));
        order.setMeasuredData(blankToNull(req.getMeasuredData()));
        order.setConclusion(blankToNull(req.getConclusion()));
        order.setCertNo(blankToNull(req.getCertNo()));
        order.setStatus("RECEIVED");
        order.setFee(procedure.getUnitFee());
        order.setRemark(blankToNull(req.getRemark()));
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        instrument.setStatus("IN_CALIBRATION");
        instrument.setUpdatedAt(LocalDateTime.now());
        instrumentRepository.save(instrument);
        return toView(order, context());
    }

    /** 录入/修订实测数据与结论 */
    @Transactional
    public Views.OrderView saveResult(Long id, CalibrationOrderDtos.ResultReq req) {
        CalibrationOrder order = require(id);
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("委托单已发放，不能再修改检定数据");
        }
        order.setMeasuredData(blankToNull(req.getMeasuredData()));
        if (req.getConclusion() != null && !req.getConclusion().isBlank()
                && !MetroDict.isValidConclusion(req.getConclusion())) {
            throw new BizException("检定结论只能是 PASS / LIMITED / FAIL");
        }
        order.setConclusion(blankToNull(req.getConclusion()));
        order.setCertNo(blankToNull(req.getCertNo()));
        order.setVerifier(blankToNull(req.getVerifier()));
        order.setChecker(blankToNull(req.getChecker()));
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return toView(order, context());
    }

    /** 推进到下一流转状态 */
    @Transactional
    public Views.OrderView advance(Long id) {
        CalibrationOrder order = require(id);
        int index = MetroDict.flowIndex(order.getStatus());
        if (index >= MetroDict.FLOW_ORDER.size() - 1) {
            throw new BizException("委托单已处于「已发放」，无法继续推进");
        }
        String next = MetroDict.FLOW_ORDER.get(index + 1);
        if ("CERTIFYING".equals(next)) {
            if (order.getConclusion() == null || order.getConclusion().isBlank()) {
                throw new BizException("出证前必须先录入检定结论（合格 / 限用 / 不合格）");
            }
            if (order.getCertNo() == null || order.getCertNo().isBlank()) {
                throw new BizException("出证前必须填写证书编号");
            }
            if (order.getCertValidUntil() == null) {
                order.setCertValidUntil(defaultValidUntil(order));
            }
        }
        if ("ISSUED".equals(next) && (order.getCertNo() == null || order.getCertNo().isBlank())) {
            throw new BizException("发放前必须完成出证并填写证书编号");
        }
        order.setStatus(next);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        if ("ISSUED".equals(next)) {
            closeInstrumentCycle(order);
        }
        return toView(order, context());
    }

    /** 回退一格流转状态（收样时撤回、核验打回重检等场景） */
    @Transactional
    public Views.OrderView rollback(Long id) {
        CalibrationOrder order = require(id);
        int index = MetroDict.flowIndex(order.getStatus());
        if (index <= 0) {
            throw new BizException("已处于流转首环节「收样」，无法再回退");
        }
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("已发放的委托单不能回退，如需更正请另开委托单");
        }
        order.setStatus(MetroDict.FLOW_ORDER.get(index - 1));
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return toView(order, context());
    }

    /** 发放后回写器具台账：更新上次检定日期与下次到期日 */
    private void closeInstrumentCycle(CalibrationOrder order) {
        if (order.getInstrumentId() == null) {
            return;
        }
        Instrument instrument = instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        if (instrument == null) {
            return;
        }
        LocalDate calDate = order.getReceiveDate() == null ? LocalDate.now() : order.getReceiveDate();
        int cycle = instrument.getCycleMonths() == null ? 12 : instrument.getCycleMonths();
        instrument.setLastCalDate(calDate);
        instrument.setNextDueDate(calDate.plusMonths(cycle));
        instrument.setStatus("IN_USE");
        instrument.setUpdatedAt(LocalDateTime.now());
        instrumentRepository.save(instrument);
    }

    private LocalDate defaultValidUntil(CalibrationOrder order) {
        Instrument instrument = order.getInstrumentId() == null ? null
                : instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        int cycle = instrument == null || instrument.getCycleMonths() == null ? 12 : instrument.getCycleMonths();
        LocalDate from = order.getReceiveDate() == null ? LocalDate.now() : order.getReceiveDate();
        return from.plusMonths(cycle);
    }

    private CalibrationOrder require(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("委托单不存在：id=" + id));
    }

    private Views.OrderView toView(CalibrationOrder order, ViewContext context) {
        Instrument instrument = context.instrumentMap.get(order.getInstrumentId());
        var procedure = context.procedureMap.get(order.getProcedureId());
        return new Views.OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getClientId(),
                context.clientName.getOrDefault(order.getClientId(), ""),
                order.getInstrumentId(),
                order.getInstNo(),
                instrument == null ? "" : instrument.getName(),
                instrument == null ? "" : instrument.getModelSpec(),
                instrument == null ? "" : instrument.getMeasureRange(),
                instrument == null ? "" : instrument.getAccuracyGrade(),
                instrument == null ? "" : instrument.getManufacturer(),
                instrument == null ? "" : instrument.getFactoryNo(),
                order.getProcedureId(),
                procedure == null ? "" : procedure.getProcNo(),
                procedure == null ? "" : procedure.getName(),
                procedure == null ? null : procedure.getUnitFee(),
                order.getReceiveDate(),
                order.getVerifier(),
                order.getChecker(),
                order.getMeasuredData(),
                order.getConclusion(),
                MetroDict.conclusionName(order.getConclusion()),
                order.getCertNo(),
                order.getStatus(),
                MetroDict.flowStatusName(order.getStatus()),
                MetroDict.flowIndex(order.getStatus()),
                order.getCertValidUntil(),
                order.getFee(),
                order.getRemark(),
                order.getUpdatedAt());
    }

    /** 一次取全，避免逐条查询 */
    private ViewContext context() {
        Map<Long, Instrument> instrumentMap = new HashMap<>();
        Map<Long, String> instrumentName = new HashMap<>();
        for (Instrument instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            instrumentMap.put(instrument.getId(), instrument);
            instrumentName.put(instrument.getId(), instrument.getName());
        }
        Map<Long, String> clientName = new HashMap<>();
        for (Client client : clientRepository.findAllByOrderByCodeAsc()) {
            clientName.put(client.getId(), client.getName());
        }
        Map<Long, Procedure> procedureMap = new HashMap<>();
        for (Procedure procedure : procedureRepository.findAllByOrderByProcNoAsc()) {
            procedureMap.put(procedure.getId(), procedure);
        }
        return new ViewContext(instrumentMap, instrumentName, clientName, procedureMap);
    }

    private record ViewContext(Map<Long, Instrument> instrumentMap,
                               Map<Long, String> instrumentName,
                               Map<Long, String> clientName,
                               Map<Long, Procedure> procedureMap) {
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static LocalDate parseDate(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(text.trim());
    }
}
