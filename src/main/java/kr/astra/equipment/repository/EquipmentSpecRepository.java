package kr.astra.equipment.repository;

import java.util.Collection;
import java.util.List;
import kr.astra.equipment.entity.EquipmentSpec;
import kr.astra.equipment.entity.SpecSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentSpecRepository extends JpaRepository<EquipmentSpec, Long> {

    List<EquipmentSpec> findAllByEquipmentIdOrderByIdAsc(Long equipmentId);

    List<EquipmentSpec> findAllByEquipmentIdInOrderByIdAsc(Collection<Long> equipmentIds);

    List<EquipmentSpec> findAllByEquipmentIdAndSourceTypeOrderByIdAsc(Long equipmentId, SpecSourceType sourceType);

    /** 실제 사양 교체용: 그 장비의 한 종류(REAL) 사양만 지움. MOCK 은 남김 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM EquipmentSpec s WHERE s.equipmentId = :equipmentId AND s.sourceType = :sourceType")
    int deleteByEquipmentIdAndSourceType(@Param("equipmentId") Long equipmentId,
                                         @Param("sourceType") SpecSourceType sourceType);
}
