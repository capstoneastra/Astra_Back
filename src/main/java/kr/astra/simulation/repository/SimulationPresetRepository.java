package kr.astra.simulation.repository;

import java.util.List;
import kr.astra.simulation.entity.SimulationPreset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationPresetRepository extends JpaRepository<SimulationPreset, String> {

    List<SimulationPreset> findAllByEquipmentIdOrderByPresetIdAsc(Long equipmentId);
}
