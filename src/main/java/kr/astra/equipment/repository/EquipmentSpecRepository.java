package kr.astra.equipment.repository;

import java.util.List;
import kr.astra.equipment.entity.EquipmentSpec;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentSpecRepository extends JpaRepository<EquipmentSpec, Long> {

    List<EquipmentSpec> findAllByEquipmentIdOrderByIdAsc(Long equipmentId);
}
