package com.metrology.lab.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.CalibrationOrder;

public interface CalibrationOrderRepository extends JpaRepository<CalibrationOrder, Long> {

    List<CalibrationOrder> findAllByOrderByReceiveDateDescIdAsc();

    List<CalibrationOrder> findAllByStatusOrderByReceiveDateAsc(String status);

    List<CalibrationOrder> findAllByClientIdOrderByReceiveDateDesc(Long clientId);

    List<CalibrationOrder> findAllByInstrumentIdOrderByReceiveDateDesc(Long instrumentId);

    long countByClientId(Long clientId);

    long countByProcedureId(Long procedureId);

    boolean existsByOrderNo(String orderNo);
}
