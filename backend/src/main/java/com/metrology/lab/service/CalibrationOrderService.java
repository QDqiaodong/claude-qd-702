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
import com.metrology.lab.domain.OrderDeviceLink;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

import jakarta.persistence.EntityManager;

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
    private final OrderDeviceService orderDeviceService;
    private final EntityManager entityManager;

    public CalibrationOrderService(CalibrationOrderRepository orderRepository,
                                   InstrumentService instrumentService,
                                   InstrumentRepository instrumentRepository,
                                   ClientRepository clientRepository,
                                   ProcedureRepository procedureRepository,
                                   StandardDeviceRepository deviceRepository,
                                   OrderDeviceService orderDeviceService,
                                   EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.instrumentService = instrumentService;
        this.instrumentRepository = instrumentRepository;
        this.clientRepository = clientRepository;
        this.procedureRepository = procedureRepository;
        this.deviceRepository = deviceRepository;
        this.orderDeviceService = orderDeviceService;
        this.entityManager = entityManager;
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
                DeviceBrief brief = buildBrief(order, context);
                items.add(new Views.QueueItemView(
                        order.getId(),
                        order.getOrderNo(),
                        order.getInstNo(),
                        context.instrumentName.getOrDefault(order.getInstrumentId(), order.getInstNo()),
                        context.clientName.getOrDefault(order.getClientId(), ""),
                        order.getReceiveDate(),
                        waiting,
                        brief.count(),
                        brief.earliest(),
                        brief.blockReasons()));
            }
            groups.add(new Views.QueueGroupView(status, MetroDict.flowStatusName(status), items.size(), items));
        }
        // 作废组不在推进链上，单独追加在队尾（仍可查询、可看挂接留痕，但不能推进）
        List<Views.QueueItemView> voidItems = new ArrayList<>();
        for (CalibrationOrder order : orderRepository.findAllByStatusOrderByReceiveDateAsc(MetroDict.STATUS_VOID)) {
            DeviceBrief brief = buildBrief(order, context);
            voidItems.add(new Views.QueueItemView(
                    order.getId(), order.getOrderNo(), order.getInstNo(),
                    context.instrumentName.getOrDefault(order.getInstrumentId(), order.getInstNo()),
                    context.clientName.getOrDefault(order.getClientId(), ""),
                    order.getReceiveDate(), null,
                    brief.count(), brief.earliest(), brief.blockReasons()));
        }
        groups.add(new Views.QueueGroupView(MetroDict.STATUS_VOID,
                MetroDict.flowStatusName(MetroDict.STATUS_VOID), voidItems.size(), voidItems));
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
            if (!"ISSUED".equals(order.getStatus()) && !MetroDict.STATUS_VOID.equals(order.getStatus())) {
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
        if (MetroDict.STATUS_VOID.equals(order.getStatus())) {
            throw new BizException("委托单已作废，不能再修改检定数据");
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

    /**
     * 推进到下一流转状态。
     *
     * <p>进入检定中必须已挂接至少一件合格标准器；其后每一次推进（核验/出证/发放）
     * 都按<b>当前时间</b>重新复核全部占用标准器的状态与证书有效期，任何一项不满足就阻止并点名。
     * 委托行先加行锁 + 期望状态判定，重复点击/并发提交只有一笔成功。
     */
    @Transactional
    public Views.OrderView advance(Long id) {
        // 锁前快照：随后拿行锁再比对，防止并发重复提交被行锁排队后连续推进多格
        String snapshot = require(id).getStatus();
        CalibrationOrder order = lockOrder(id);
        String current = order.getStatus();
        if (MetroDict.STATUS_VOID.equals(current)) {
            throw new BizException("委托单已作废，不能继续流转");
        }
        if (!snapshot.equals(current)) {
            throw new BizException("委托刚被另一方推进到「" + MetroDict.flowStatusName(current)
                    + "」，本次请求基于旧状态，已取消；请刷新后按最新状态操作");
        }
        int index = MetroDict.flowIndex(current);
        if (!MetroDict.isFlowStatus(current) || index >= MetroDict.FLOW_ORDER.size() - 1) {
            throw new BizException("委托单已处于「已发放」，无法继续推进");
        }
        String next = MetroDict.FLOW_ORDER.get(index + 1);

        // 标准器放行校验：进入检定中要求挂接；其余环节逐台复核（历史无挂接委托走旧流程）
        orderDeviceService.validateForFlow(order, "CALIBRATING".equals(next));

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
            // 发放 = 业务闭环结束：释放占用，同时挂接行保留为 ISSUED 历史留痕，重新打开仍可见
            orderDeviceService.releaseAll(id, OrderDeviceService.REASON_ISSUED);
        }
        return toView(order, context());
    }

    /**
     * 回退一格流转状态（核验打回重检等场景）。
     *
     * <p>检定中 → 收样：检定尚未真正开展，释放全部标准器占用（行保留为历史），
     * 检定员可重新选择后再推进；核验 → 检定中、出证 → 核验仍处在使用期，占用保留不动。
     */
    @Transactional
    public Views.OrderView rollback(Long id) {
        String snapshot = require(id).getStatus();
        CalibrationOrder order = lockOrder(id);
        String current = order.getStatus();
        if (MetroDict.STATUS_VOID.equals(current)) {
            throw new BizException("已作废的委托单不能回退");
        }
        if (!snapshot.equals(current)) {
            throw new BizException("委托状态刚被另一方更新（现为「" + MetroDict.flowStatusName(current)
                    + "」），本次回退已取消；请刷新后重试");
        }
        int index = MetroDict.flowIndex(current);
        if (!MetroDict.isFlowStatus(current) || index <= 0) {
            throw new BizException("已处于流转首环节「收样」，无法再回退");
        }
        if ("ISSUED".equals(current)) {
            throw new BizException("已发放的委托单不能回退，如需更正请另开委托单");
        }
        String prev = MetroDict.FLOW_ORDER.get(index - 1);
        order.setStatus(prev);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        if ("RECEIVED".equals(prev)) {
            orderDeviceService.releaseAll(id, OrderDeviceService.REASON_ROLLBACK);
        }
        return toView(order, context());
    }

    /**
     * 委托作废（收样后客户撤检 / 无法继续的异常件）。
     * 已发放不可作废；作废释放全部标准器占用，其他委托可立即选用。
     */
    @Transactional
    public Views.OrderView voidOrder(Long id) {
        String snapshot = require(id).getStatus();
        CalibrationOrder order = lockOrder(id);
        String current = order.getStatus();
        if (!snapshot.equals(current)) {
            throw new BizException("委托状态刚被另一方更新（现为「" + MetroDict.flowStatusName(current)
                    + "」），本次作废已取消；请刷新后确认");
        }
        if (MetroDict.STATUS_VOID.equals(current)) {
            throw new BizException("委托单已作废，请勿重复操作");
        }
        if ("ISSUED".equals(current)) {
            throw new BizException("已发放的委托单不能作废，如需更正请另开委托单");
        }
        order.setStatus(MetroDict.STATUS_VOID);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        orderDeviceService.releaseAll(id, OrderDeviceService.REASON_VOID);
        // 受检器具退出送检占用，回到在用，由台账后续安排
        restoreInstrument(order);
        return toView(order, context());
    }

    /** 作废时把器具从「送检中」恢复为在用（不回写检定周期，检定未完成） */
    private void restoreInstrument(CalibrationOrder order) {
        if (order.getInstrumentId() == null) {
            return;
        }
        Instrument instrument = instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        if (instrument != null && "IN_CALIBRATION".equals(instrument.getStatus())) {
            instrument.setStatus("IN_USE");
            instrument.setUpdatedAt(LocalDateTime.now());
            instrumentRepository.save(instrument);
        }
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

    /** 行锁加载：写操作串行化，配合实体上的 @Version 乐观锁 */
    private CalibrationOrder lockOrder(Long id) {
        CalibrationOrder order = require(id);
        // FOR UPDATE 加行锁并强制按数据库当前内容刷新一级缓存，
        // 使「锁前快照 vs 锁后状态」的并发守卫能确定性命中（而不是等 flush 时才撞乐观锁）
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return order;
    }

    private Views.OrderView toView(CalibrationOrder order, ViewContext context) {
        Instrument instrument = context.instrumentMap.get(order.getInstrumentId());
        var procedure = context.procedureMap.get(order.getProcedureId());
        DeviceBrief brief = buildBrief(order, context);
        int stepIndex = MetroDict.STATUS_VOID.equals(order.getStatus()) ? -1 : MetroDict.flowIndex(order.getStatus());
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
                stepIndex,
                order.getCertValidUntil(),
                order.getFee(),
                order.getRemark(),
                order.getUpdatedAt(),
                brief.activeViews(),
                brief.releasedViews(),
                brief.count(),
                brief.earliest(),
                brief.legacy(),
                OrderDeviceService.isEditableStatus(order.getStatus()),
                brief.blockReasons());
    }

    /**
     * 一次取全，避免逐条查询。挂接/占用/标准器信息同样批量装入，
     * 列表与详情拿到的编号、有效期、状态都来自同一份当前快照。
     */
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
        Map<Long, StandardDevice> deviceMap = new HashMap<>();
        for (StandardDevice device : deviceRepository.findAllByOrderByCertValidUntilAsc()) {
            deviceMap.put(device.getId(), device);
        }
        Map<Long, CalibrationOrder> orderMap = new HashMap<>();
        for (CalibrationOrder o : orderRepository.findAllByOrderByReceiveDateDescIdAsc()) {
            orderMap.put(o.getId(), o);
        }
        // orderId -> 该委托全部挂接行（含历史）
        Map<Long, List<OrderDeviceLink>> linksByOrder = new HashMap<>();
        for (OrderDeviceLink link : orderDeviceService.allLinks()) {
            linksByOrder.computeIfAbsent(link.getOrderId(), k -> new ArrayList<>()).add(link);
        }
        return new ViewContext(instrumentMap, instrumentName, clientName, procedureMap,
                deviceMap, orderMap, linksByOrder);
    }

    /** 挂接摘要：数量 / 最早到期 / 不可放行原因 / 挂接视图列表 */
    private record DeviceBrief(int count, LocalDate earliest, boolean legacy,
                               List<Views.DeviceLinkView> activeViews,
                               List<Views.DeviceLinkView> releasedViews,
                               List<String> blockReasons) {
    }

    private record ViewContext(Map<Long, Instrument> instrumentMap,
                               Map<Long, String> instrumentName,
                               Map<Long, String> clientName,
                               Map<Long, Procedure> procedureMap,
                               Map<Long, StandardDevice> deviceMap,
                               Map<Long, CalibrationOrder> orderMap,
                               Map<Long, List<OrderDeviceLink>> linksByOrder) {
    }

    private DeviceBrief buildBrief(CalibrationOrder order, ViewContext context) {
        List<OrderDeviceLink> links = context.linksByOrder()
                .getOrDefault(order.getId(), List.of());
        List<Views.DeviceLinkView> activeViews = new ArrayList<>();
        List<Views.DeviceLinkView> releasedViews = new ArrayList<>();
        LocalDate earliest = null;
        LocalDate today = LocalDate.now();
        List<String> blocks = new ArrayList<>();

        for (OrderDeviceLink link : links) {
            StandardDevice device = context.deviceMap().get(link.getDeviceId());
            boolean active = link.getReleasedAt() == null;
            String reason;
            if (device == null) {
                reason = "挂接记录异常：标准器 id=" + link.getDeviceId() + " 已不存在";
            } else {
                reason = OrderDeviceService.unusableReason(device, today);
                if (active && device.getCertValidUntil() != null
                        && (earliest == null || device.getCertValidUntil().isBefore(earliest))) {
                    earliest = device.getCertValidUntil();
                }
            }
            Views.DeviceLinkView view = briefLinkView(link, device, order, reason);
            if (active) {
                activeViews.add(view);
                if (reason != null) {
                    blocks.add("标准器 " + (device == null ? ("id=" + link.getDeviceId()) : device.getDeviceNo())
                            + "（" + (device == null ? "已删除" : device.getName()) + "）" + reason);
                }
            } else {
                releasedViews.add(view);
            }
        }
        releasedViews.sort((a, b) -> b.releasedAt() == null || a.releasedAt() == null
                ? 0 : b.releasedAt().compareTo(a.releasedAt()));
        // 收样且无活动挂接：无论是新单还是检定退回收样，重新推进前都必须再挂接
        if (activeViews.isEmpty() && "RECEIVED".equals(order.getStatus())) {
            blocks.add(links.isEmpty()
                    ? "尚未挂接标准器，进入检定前需先挂接"
                    : "检定退回后标准器已释放，重新推进前需重新挂接");
        }
        return new DeviceBrief(activeViews.size(), earliest, links.isEmpty(),
                activeViews, releasedViews, List.copyOf(blocks));
    }

    private static Views.DeviceLinkView briefLinkView(OrderDeviceLink link, StandardDevice device,
                                                      CalibrationOrder order, String reason) {
        LocalDate until = device == null ? null : device.getCertValidUntil();
        Integer remain = until == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), until);
        boolean expired = remain != null && remain < 0;
        boolean expiring = remain != null && remain >= 0
                && remain <= MetroDict.DEVICE_VALID_WARN_DAYS;
        boolean active = link.getReleasedAt() == null;
        return new Views.DeviceLinkView(
                link.getId(), link.getDeviceId(),
                device == null ? "STD-?" : device.getDeviceNo(),
                device == null ? "（台账中已不存在）" : device.getName(),
                device == null ? null : device.getModel(),
                device == null ? null : device.getTraceOrg(),
                until, remain, expired, expiring,
                device == null ? null : device.getStatus(),
                device == null ? "" : MetroDict.deviceStatusName(device.getStatus()),
                link.getAttachedAt(), link.getReleasedAt(),
                link.getReleaseReason(), MetroDict.releaseReasonName(link.getReleaseReason()),
                active,
                active ? order.getId() : null,
                active ? order.getOrderNo() : null,
                reason == null, reason);
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
