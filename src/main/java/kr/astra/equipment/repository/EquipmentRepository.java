package kr.astra.equipment.repository;

import java.util.Optional;
import kr.astra.equipment.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    /** 비활성(삭제 처리)된 장비는 조회하지 않음 */
    Optional<Equipment> findByIdAndActiveTrue(Long id);
}
