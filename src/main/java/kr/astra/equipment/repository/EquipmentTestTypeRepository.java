package kr.astra.equipment.repository;

import java.util.Collection;
import java.util.List;
import kr.astra.equipment.entity.EquipmentTestType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentTestTypeRepository extends JpaRepository<EquipmentTestType, EquipmentTestType.Key> {

    List<EquipmentTestType> findAllByEquipmentIdIn(Collection<Long> equipmentIds);
}
