package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.CalibrationOrder;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.OrderDeviceLink;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.OrderDeviceLinkRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * 标准器挂接与占用服务：挂接/替换/解除、占用判定、流转放行前的有效期复核。
 *
 * <p>并发与一致性的四道防线（任何一道都不是摆设）：
 * <ol>
 *   <li>调整清单与流转放行都先 {@code FOR UPDATE} 锁定委托行，同一委托的写操作串行化；</li>
 *   <li>校验标准器时逐台 {@code FOR UPDATE} 锁设备行（按 id 升序加锁防死锁），
 *       期间「停用 / 改证 / 送检」无法插入；</li>
 *   <li>占用关系落库撞 {@code uk_device_active} 唯一索引 —— 两人同时抢占同一标准器，
 *       后提交者 INSERT 报键冲突，整个事务回滚，委托原数据一行不动；</li>
 *   <li>委托上的 {@code @Version} 乐观锁兜底重复点击 / 并发推进。</li>
 * </ol>
 */
@Service
public class OrderDeviceService {

    public static final String REASON_REPLACE = "REPLACE";
    public static final String REASON_ROLLBACK = "ROLLBACK_RECEIVED";
    public static final String REASON_VOID = "VOID";
    public static final String REASON_ISSUED = "ISSUED";

    private final OrderDeviceLinkRepository linkRepository;
    private final StandardDeviceRepository deviceRepository;
    private final CalibrationOrderRepository orderRepository;
    private final EntityManager entityManager;

    public OrderDeviceService(OrderDeviceLinkRepository linkRepository,
                              StandardDeviceRepository deviceRepository,
                              CalibrationOrderRepository orderRepository,
                              EntityManager entityManager) {
        this.linkRepository = linkRepository;
        this.deviceRepository = deviceRepository;
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
    }

    // ============================ 视图装配 ============================

    /** 读路径用的全站占用快照：deviceId -> 活动挂接行（正常至多一条） */
    @Transactional(readOnly = true)
    public Map<Long, OrderDeviceLink> activeOccupancy() {
        Map<Long, OrderDeviceLink> map = new HashMap<>();
        for (OrderDeviceLink link : linkRepository.findAllByReleasedAtIsNull()) {
            map.putIfAbsent(link.getDeviceId(), link);
        }
        return map;
    }

    @Transactional(readOnly = true)
    public List<OrderDeviceLink> allLinks() {
        return linkRepository.findAll();
    }

    /**
     * 挂接抽屉：当前清单 + 全部候选标准器（标注占用方与不可选用原因）。
     * 所有状态/有效期都按<b>当前</b>数据库内容计算，刷新页面不能绕过。
     */
    @Transactional(readOnly = true)
    public Views.OrderDevicesView devicesOf(Long orderId) {
        CalibrationOrder order = requireOrder(orderId);
        Map<Long, StandardDevice> deviceMap = deviceMap();
        Map<Long, OrderDeviceLink> occupancy = activeOccupancy();
        Map<Long, CalibrationOrder> orderMap = orderMap();

        List<OrderDeviceLink> links = linkRepository.findAllByOrderIdOrderByAttachedAtAscIdAsc(orderId);
        List<Views.DeviceLinkView> active = new ArrayList<>();
        List<Views.DeviceLinkView> released = new ArrayList<>();
        for (OrderDeviceLink link : links) {
            Views.DeviceLinkView view = toLinkView(link, deviceMap, occupancy, orderMap);
            if (link.getReleasedAt() == null) {
                active.add(view);
            } else {
                released.add(view);
            }
        }

        // 候选 = 全部标准器（含本委托已挂的，勾选框即为挂接/解除入口）；
        // 被别的委托占用或自身停用过期的行仍展示但不可勾选，并给出具体原因
        List<Views.DeviceLinkView> candidates = new ArrayList<>();
        for (StandardDevice device : deviceMap.values()) {
            candidates.add(toCandidateView(device, occupancy, orderMap));
        }
        candidates.sort(Comparator
                .comparing((Views.DeviceLinkView v) -> !v.selectable() && !v.active())
                .thenComparing(Views.DeviceLinkView::deviceNo));

        return new Views.OrderDevicesView(
                order.getId(), order.getOrderNo(), order.getStatus(),
                MetroDict.flowStatusName(order.getStatus()),
                isEditableStatus(order.getStatus()), active, released, candidates);
    }

    // ============================ 挂接 / 替换 / 解除 ============================

    /**
     * 全量保存挂接清单（一个事务完成）：
     * 先锁委托 → 仅收样允许调整 → 按 id 升序锁设备 → 逐台判定占用/状态/证书
     * → 释放被移除的活动行 → 插入新增行（唯一索引兜底并发抢占）。
     *
     * <p>任何一项不满足整体回滚：失败方委托与已填数据原样保留，修正后可重新提交。
     */
    @Transactional
    public Views.OrderDevicesView saveAttachments(Long orderId, CalibrationOrderDtos.DeviceAttachReq req) {
        CalibrationOrder order = lockOrder(orderId);
        if (MetroDict.STATUS_VOID.equals(order.getStatus())) {
            throw new BizException("委托已作废，挂接清单不能再调整");
        }
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("委托已发放，挂接清单已随证书留痕，不能再调整");
        }
        if (!isEditableStatus(order.getStatus())) {
            throw new BizException("委托已进入「" + MetroDict.flowStatusName(order.getStatus())
                    + "」环节，使用过的标准器不能再静默调整；如需更换请先回退到收样");
        }

        Set<Long> requested = new TreeSet<>();
        if (req != null && req.getDeviceIds() != null) {
            for (Long id : req.getDeviceIds()) {
                if (id != null) {
                    requested.add(id);
                }
            }
        }

        // 按 id 升序逐台加行锁后再判定，避免两人交叉加锁造成死锁
        Map<Long, StandardDevice> locked = new HashMap<>();
        for (Long deviceId : requested) {
            locked.put(deviceId, lockDevice(deviceId));
        }
        Map<Long, OrderDeviceLink> occupancy = activeOccupancy();

        List<OrderDeviceLink> current =
                linkRepository.findAllByOrderIdAndReleasedAtIsNullOrderByIdAsc(orderId);
        Set<Long> currentIds = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();

        // 1) 逐台校验新清单（状态、证书、占用冲突）
        for (Long deviceId : requested) {
            StandardDevice device = locked.get(deviceId);
            OrderDeviceLink holder = occupancy.get(deviceId);
            if (holder != null && !holder.getOrderId().equals(orderId)) {
                CalibrationOrder holderOrder = orderRepository.findById(holder.getOrderId()).orElse(null);
                String no = holderOrder == null ? "另一笔未完成委托" : holderOrder.getOrderNo();
                throw new BizException("标准器 " + device.getDeviceNo() + "（" + device.getName()
                        + "）已被未完成委托 " + no + " 占用，本委托不能抢占");
            }
            String problem = unusableReason(device);
            if (problem != null) {
                throw new BizException("标准器 " + device.getDeviceNo() + "（" + device.getName()
                        + "）" + problem + "，不能挂接");
            }
        }

        // 2) 解除：不在新清单里的活动行置为已释放
        for (OrderDeviceLink link : current) {
            currentIds.add(link.getDeviceId());
            if (!requested.contains(link.getDeviceId())) {
                link.setReleasedAt(now);
                link.setReleaseReason(REASON_REPLACE);
                linkRepository.save(link);
            }
        }

        // 3) 挂接：新增的设备插入活动行（uk_device_active 兜底：并发抢占时只有一笔成功）
        for (Long deviceId : requested) {
            if (currentIds.contains(deviceId)) {
                continue;
            }
            OrderDeviceLink link = new OrderDeviceLink();
            link.setOrderId(orderId);
            link.setDeviceId(deviceId);
            link.setAttachedAt(now);
            try {
                linkRepository.saveAndFlush(link);
            } catch (DataIntegrityViolationException e) {
                throw new BizException("标准器 " + locked.get(deviceId).getDeviceNo()
                        + " 刚被另一笔委托抢先占用，请刷新清单后重新选择");
            }
        }
        return devicesOf(orderId);
    }

    /** 释放一笔委托的全部活动占用（回退到收样 / 作废 / 发放留痕），调用方须持有委托事务 */
    public void releaseAll(Long orderId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        for (OrderDeviceLink link : linkRepository.findAllByOrderIdAndReleasedAtIsNullOrderByIdAsc(orderId)) {
            link.setReleasedAt(now);
            link.setReleaseReason(reason);
            linkRepository.save(link);
        }
    }

    // ============================ 流转放行校验 ============================

    /**
     * 流转放行前按<b>当前时间</b>重新校验全部活动标准器。
     * 逐台加行锁，任何一台停用/送检/过期/记录异常都抛出带编号的明确原因并阻止流转。
     *
     * @param requireDevices true = 进入检定中（收样→检定中），要求至少挂接一件标准器；
     *                       后续环节为 false（兼容从未挂过标准器的历史委托：无挂接则放行旧流程）
     */
    public void validateForFlow(CalibrationOrder order, boolean requireDevices) {
        List<OrderDeviceLink> links =
                linkRepository.findAllByOrderIdAndReleasedAtIsNullOrderByIdAsc(order.getId());

        if (links.isEmpty()) {
            if (requireDevices) {
                throw new BizException("进入检定前必须先挂接至少一件状态在用、证书在有效期内的标准器");
            }
            // 从未挂过标准器的历史委托：保持原有流程可查询、可完成
            return;
        }

        LocalDate today = LocalDate.now();
        for (OrderDeviceLink link : links) {
            StandardDevice device = deviceRepository.findById(link.getDeviceId()).orElse(null);
            if (device == null) {
                throw new BizException("不能放行：挂接记录异常，标准器 id=" + link.getDeviceId()
                        + " 已从台账不存在。请在收样/重检后重新挂接");
            }
            entityManager.refresh(device, LockModeType.PESSIMISTIC_WRITE);
            String problem = unusableReason(device, today);
            if (problem != null) {
                throw new BizException("不能放行：标准器 " + device.getDeviceNo() + "（" + device.getName()
                        + "）" + problem + "。请在收样/重检后更换标准器再提交");
            }
        }
    }

    // ============================ 内部工具 ============================

    /** 以当前日期判定标准器能否用于检定：null = 可以 */
    public static String unusableReason(StandardDevice device) {
        return unusableReason(device, LocalDate.now());
    }

    /** 指定日期判定（放行校验用当前时间，保证不被缓存/刷新绕过） */
    public static String unusableReason(StandardDevice device, LocalDate today) {
        if (device == null) {
            return "挂接记录异常（标准器不存在）";
        }
        if ("STOPPED".equals(device.getStatus())) {
            return "当前状态为停用";
        }
        if ("TRACING".equals(device.getStatus())) {
            return "当前状态为送检中，不在所内";
        }
        LocalDate until = device.getCertValidUntil();
        if (until == null) {
            return "未登记溯源证书有效期";
        }
        if (until.isBefore(today)) {
            return "溯源证书已于 " + until + " 过期";
        }
        return null;
    }

    public static boolean isEditableStatus(String status) {
        return "RECEIVED".equals(status);
    }

    private CalibrationOrder lockOrder(Long id) {
        CalibrationOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        return order;
    }

    private StandardDevice lockDevice(Long id) {
        StandardDevice device = deviceRepository.findById(id).orElse(null);
        if (device == null) {
            throw new BizException("挂接记录异常：标准器 id=" + id + " 在台账中不存在，请重新选择");
        }
        entityManager.refresh(device, LockModeType.PESSIMISTIC_WRITE);
        return device;
    }

    private CalibrationOrder requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
    }

    private Map<Long, StandardDevice> deviceMap() {
        Map<Long, StandardDevice> map = new HashMap<>();
        for (StandardDevice device : deviceRepository.findAllByOrderByCertValidUntilAsc()) {
            map.put(device.getId(), device);
        }
        return map;
    }

    private Map<Long, CalibrationOrder> orderMap() {
        Map<Long, CalibrationOrder> map = new HashMap<>();
        for (CalibrationOrder order : orderRepository.findAllByOrderByReceiveDateDescIdAsc()) {
            map.put(order.getId(), order);
        }
        return map;
    }

    private Views.DeviceLinkView toLinkView(OrderDeviceLink link,
                                            Map<Long, StandardDevice> deviceMap,
                                            Map<Long, OrderDeviceLink> occupancy,
                                            Map<Long, CalibrationOrder> orderMap) {
        StandardDevice device = deviceMap.get(link.getDeviceId());
        boolean active = link.getReleasedAt() == null;
        Long occupiedBy = null;
        String occupiedByNo = null;
        boolean selectable = true;
        String reason = null;

        if (device == null) {
            reason = "挂接记录异常：标准器已从台账删除";
            selectable = false;
        } else {
            String problem = unusableReason(device);
            if (problem != null) {
                reason = problem;
                selectable = false;
            }
            OrderDeviceLink holder = occupancy.get(device.getId());
            if (holder != null) {
                occupiedBy = holder.getOrderId();
                CalibrationOrder holderOrder = orderMap.get(holder.getOrderId());
                occupiedByNo = holderOrder == null ? ("#" + holder.getOrderId()) : holderOrder.getOrderNo();
                if (!holder.getOrderId().equals(link.getOrderId())) {
                    selectable = false;
                    reason = reason == null
                            ? "已被委托 " + occupiedByNo + " 占用" : reason + "；且被委托 " + occupiedByNo + " 占用";
                }
            }
            return baseView(link, device, active, occupiedBy, occupiedByNo, selectable, reason);        }
        return new Views.DeviceLinkView(link.getId(), link.getDeviceId(),
                "STD-?", "（台账中已不存在）", null, null, null, null, false, false,
                null, "", link.getAttachedAt(), link.getReleasedAt(),
                link.getReleaseReason(), MetroDict.releaseReasonName(link.getReleaseReason()),
                active, occupiedBy, occupiedByNo, selectable, reason);
    }

    /**
     * 候选抽屉行：全部标准器。
     * selectable=false 的两类：① 被别的委托占用；② 空闲但自身停用/过期/异常——前端禁选。
     * 本委托自己的活动挂接行：仍 selectable=true，取消勾选即解除（停用/过期的已挂件也要允许解除重选）。
     */
    private Views.DeviceLinkView toCandidateView(StandardDevice device,
                                                 Map<Long, OrderDeviceLink> occupancy,
                                                 Map<Long, CalibrationOrder> orderMap) {
        OrderDeviceLink link = new OrderDeviceLink();
        link.setDeviceId(device.getId());
        link.setAttachedAt(null);
        Long occupiedBy = null;
        String occupiedByNo = null;
        boolean active = false;
        boolean selfActive = false;
        String reason = unusableReason(device);
        OrderDeviceLink holder = occupancy.get(device.getId());
        if (holder != null) {
            occupiedBy = holder.getOrderId();
            CalibrationOrder holderOrder = orderMap.get(holder.getOrderId());
            occupiedByNo = holderOrder == null ? ("#" + holder.getOrderId()) : holderOrder.getOrderNo();
            // 本委托自己的活动挂接：可勾选（保留）也可取消（解除）
            selfActive = active = true;
            link.setAttachedAt(holder.getAttachedAt());
            link.setReleaseReason(holder.getReleaseReason());
            link.setReleasedAt(holder.getReleasedAt());
            link.setOrderId(holder.getOrderId());
            link.setId(holder.getId());
        }
        boolean selectable;
        if (selfActive) {
            // 已挂行即便停用/过期也允许取消勾选（解除），保存时由服务端拦截"继续保留不合格件"
            selectable = true;
        } else if (holder != null) {
            // 空闲性优先级最高：别人占用，不能选
            selectable = false;
            String conflict = "已被委托 " + occupiedByNo + " 占用";
            reason = reason == null ? conflict : reason + "；" + conflict;
        } else {
            // 未被占用：自身合格才能选
            selectable = reason == null;
        }
        return baseView(link, device, active, occupiedBy, occupiedByNo, selectable, reason);
    }

    private Views.DeviceLinkView baseView(OrderDeviceLink link, StandardDevice device, boolean active,
                                          Long occupiedBy, String occupiedByNo,
                                          boolean selectable, String reason) {
        LocalDate validUntil = device.getCertValidUntil();
        Integer remainDays = validUntil == null ? null
                : (int) ChronoUnit.DAYS.between(LocalDate.now(), validUntil);
        boolean expired = remainDays != null && remainDays < 0;
        boolean expiring = remainDays != null && remainDays >= 0
                && remainDays <= MetroDict.DEVICE_VALID_WARN_DAYS;
        return new Views.DeviceLinkView(
                link.getId(), device.getId(), device.getDeviceNo(), device.getName(), device.getModel(),
                device.getTraceOrg(), validUntil, remainDays, expired, expiring,
                device.getStatus(), MetroDict.deviceStatusName(device.getStatus()),
                link.getAttachedAt(), link.getReleasedAt(),
                link.getReleaseReason(), MetroDict.releaseReasonName(link.getReleaseReason()),
                active, occupiedBy, occupiedByNo,
                selectable, reason);
    }
}
