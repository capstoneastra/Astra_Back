package kr.astra.equipment.repository;

import java.util.Optional;
import kr.astra.equipment.entity.Equipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Equipment e where e.id = :id and e.active = true")
    Optional<Equipment> lockActive(@org.springframework.data.repository.query.Param("id") Long id);

    /** 비활성(삭제 처리)된 장비는 조회하지 않음 */
    Optional<Equipment> findByIdAndActiveTrue(Long id);

    /** 한 기관의 활성 장비 (GET /institutions/me/equipment) */
    Page<Equipment> findAllByInstitutionIdAndActiveTrue(Long institutionId, Pageable pageable);
}
