package kr.astra.simulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 유한한 입력이라도 계산 중 Infinity/NaN 이 되면 500 이 아니라 InvalidInput(→ 400) 이어야 합니다 */
class SimulationEngineTest {

    private final SimulationEngine engine = new SimulationEngine();

    @Test
    void 선형_탄성_계산이_넘치면_InvalidInput() {
        assertThatThrownBy(() -> engine.run("LINEAR_FORCE_DISPLACEMENT",
                Map.of("specimenStiffnessNPerMm", 1e300, "maxDisplacementMm", 1e300), List.of()))
                .isInstanceOf(SimulationEngine.InvalidInput.class);
    }

    @Test
    void SDOF_질량_합이_넘치면_InvalidInput() {
        assertThatThrownBy(() -> engine.run("SDOF_BASE_EXCITATION",
                Map.of("massKg", 1e308, "fixtureMassKg", 1e308, "stiffnessNPerM", 100000, "dampingRatio", 0.05,
                        "frequencyMinHz", 20, "frequencyMaxHz", 2000), List.of()))
                .isInstanceOf(SimulationEngine.InvalidInput.class);
    }

    @Test
    void 전력_계산이_넘치면_InvalidInput() {
        assertThatThrownBy(() -> engine.run("IDEAL_DC_POWER", Map.of("voltageV", 1e200, "currentA", 1e200), List.of()))
                .isInstanceOf(SimulationEngine.InvalidInput.class);
    }

    @Test
    void 정상_입력은_그대로_계산된다() {
        Map<String, Object> out = engine.run("LINEAR_FORCE_DISPLACEMENT",
                Map.of("specimenStiffnessNPerMm", 2, "maxDisplacementMm", 5), List.of());
        assertThat(((Map<?, ?>) out.get("summary")).get("maxForceN")).isEqualTo(10.0);
    }
}
