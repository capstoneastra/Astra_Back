package kr.astra.simulation.repository;

import java.util.List;
import kr.astra.simulation.entity.SimulationModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationModelRepository extends JpaRepository<SimulationModel, String> {

    List<SimulationModel> findAllByOrderByCapabilityAscModelTypeAsc();
}
