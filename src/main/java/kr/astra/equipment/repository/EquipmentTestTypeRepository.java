package kr.astra.equipment.repository;

import java.util.Collection;
import java.util.List;
import kr.astra.equipment.entity.EquipmentTestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentTestTypeRepository extends JpaRepository<EquipmentTestType, EquipmentTestType.Key> {

    List<EquipmentTestType> findAllByEquipmentIdIn(Collection<Long> equipmentIds);

    List<EquipmentTestType> findAllByEquipmentIdOrderByTestTypeCodeAsc(Long equipmentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM EquipmentTestType t WHERE t.equipmentId = :equipmentId")
    int deleteByEquipmentId(@Param("equipmentId") Long equipmentId);
}
