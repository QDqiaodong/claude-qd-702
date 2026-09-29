package com.metrology.lab.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.OrderDeviceLink;

public interface OrderDeviceLinkRepository extends JpaRepository<OrderDeviceLink, Long> {

    /** 委托下的全部挂接行（含已释放的历史行），按挂接时间排序 */
    List<OrderDeviceLink> findAllByOrderIdOrderByAttachedAtAscIdAsc(Long orderId);

    /** 委托下的活动行（released_at IS NULL）—— 即当前占用 */
    List<OrderDeviceLink> findAllByOrderIdAndReleasedAtIsNullOrderByIdAsc(Long orderId);

    /** 一台标准器的活动行：非空即表示被某笔委托占用 */
    List<OrderDeviceLink> findAllByDeviceIdAndReleasedAtIsNull(Long deviceId);

    /** 所有活动行（全站占用快照） */
    List<OrderDeviceLink> findAllByReleasedAtIsNull();
}
