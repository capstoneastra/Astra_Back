package kr.astra.equipment.repository;

import kr.astra.equipment.entity.EquipmentChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentChangeLogRepository extends JpaRepository<EquipmentChangeLog, Long> {

    /** 최신순 */
    Page<EquipmentChangeLog> findAllByEquipmentIdOrderByIdDesc(Long equipmentId, Pageable pageable);
}
