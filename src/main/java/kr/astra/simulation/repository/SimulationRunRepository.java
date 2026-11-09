package kr.astra.simulation.repository;

import kr.astra.simulation.entity.SimulationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    Page<SimulationRun> findAllByUserId(Long userId, Pageable pageable);

    Page<SimulationRun> findAllByUserIdAndEquipmentId(Long userId, Long equipmentId, Pageable pageable);
}
