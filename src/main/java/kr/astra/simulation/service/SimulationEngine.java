package kr.astra.simulation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 가상 시뮬레이션 계산 (전부 DEMO). 실장비 성능·시험 적합성을 의미하지 않습니다.
 * <ul>
 *   <li>CALCULATED_DEMO 9종: 교과서 공식으로 그래프 + 요약값 계산</li>
 *   <li>RULE_BASED_DEMO 4종: 입력값을 프리셋의 시연 사양(specs_snapshot, MOCK)과 비교 → MET / NOT_MET / UNKNOWN</li>
 *   <li>WORKFLOW_DEMO·VISUALIZATION_DEMO: 계산 없음 (프리셋 표시만, 실행하면 NOT_EXECUTED)</li>
 * </ul>
 * 출력 형식: { "chart": { "xLabel", "yLabel", "series": [[x, y], ...] }, "summary": {...} }
 * 규칙형은 { "checks": [...], "summary": { "verdict" } }
 */
@Component
public class SimulationEngine {

    private static final double G = 9.80665;

    public static final Set<String> SUPPORTED = Set.of(
            "SDOF_BASE_EXCITATION", "CHAMBER_SETPOINT_PROFILE", "LINEAR_FORCE_DISPLACEMENT", "IMPACT_ENERGY_BUDGET",
            "BATTERY_IDEAL_CC", "PRESS_LOAD_PROFILE", "XRAY_ATTENUATION_TOY", "SOUND_LEVEL_DECIBEL_VIEW",
            "IDEAL_DC_POWER",
            "WORK_ENVELOPE_FIT", "ROBOT_REACH_CHECK", "MEASUREMENT_VOLUME_FIT", "RF_FREQUENCY_COVERAGE");

    /** 입력값이 없거나 범위를 벗어남 → 서비스가 400 VALIDATION_FAILED 로 바꿈 (field 가 null 이면 특정 입력 아님) */
    public static class InvalidInput extends RuntimeException {
        private final String field;

        InvalidInput(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    public boolean supports(String modelType) {
        return SUPPORTED.contains(modelType);
    }

    /**
     * @param in    입력 (프리셋 값 + 사용자가 바꾼 값)
     * @param specs 프리셋의 시연 사양 [{key, value | min, max, unit}]
     */
    public Map<String, Object> run(String modelType, Map<String, Object> in, List<Map<String, Object>> specs) {
        return switch (modelType) {
            case "SDOF_BASE_EXCITATION" -> sdof(in);
            case "CHAMBER_SETPOINT_PROFILE" -> chamber(in);
            case "LINEAR_FORCE_DISPLACEMENT" -> linearForce(in);
            case "IMPACT_ENERGY_BUDGET" -> impact(in);
            case "BATTERY_IDEAL_CC" -> battery(in);
            case "PRESS_LOAD_PROFILE" -> press(in);
            case "XRAY_ATTENUATION_TOY" -> xray(in);
            case "SOUND_LEVEL_DECIBEL_VIEW" -> sound(in);
            case "IDEAL_DC_POWER" -> dcPower(in);
            case "WORK_ENVELOPE_FIT" -> fits(in, specs, List.of(
                    new Check("partWidthMm", "work_envelope_x", "작업영역 X"),
                    new Check("partDepthMm", "work_envelope_y", "작업영역 Y")));
            case "ROBOT_REACH_CHECK" -> fits(in, specs, List.of(
                    new Check("targetDistanceMm", "robot_reach", "작업 반경")));
            case "MEASUREMENT_VOLUME_FIT" -> fits(in, specs, List.of(
                    new Check("partXmm", "measurement_range_x", "측정 범위 X"),
                    new Check("partYmm", "measurement_range_y", "측정 범위 Y"),
                    new Check("partZmm", "measurement_range_z", "측정 범위 Z")));
            case "RF_FREQUENCY_COVERAGE" -> rfCoverage(in, specs);
            default -> throw new IllegalArgumentException("계산을 지원하지 않는 모델: " + modelType);
        };
    }

    // ------------------------------------------------------------ CALCULATED_DEMO

    /**
     * 1자유도 기저 가진 전달률. fn = √(k / m) / 2π,  r = f / fn,
     * T = √((1 + (2ζr)²) / ((1 − r²)² + (2ζr)²)).  m = 시료 + 고정구 질량
     */
    Map<String, Object> sdof(Map<String, Object> in) {
        double mass = positive(in, "massKg") + nonNegative(in, "fixtureMassKg");
        double k = positive(in, "stiffnessNPerM");
        double zeta = between(in, "dampingRatio", 0.001, 0.999);
        double fMin = positive(in, "frequencyMinHz");
        double fMax = greaterThan(in, "frequencyMaxHz", fMin);

        double fn = Math.sqrt(k / mass) / (2 * Math.PI);
        TreeSet<Double> freqs = new TreeSet<>(logSpace(fMin, fMax, 50));
        if (fn >= fMin && fn <= fMax) {
            freqs.add(fn);
        }
        List<double[]> series = new ArrayList<>();
        double peak = 0;
        for (double f : freqs) {
            double r = f / fn;
            double two = 2 * zeta * r;
            double t = Math.sqrt((1 + two * two) / (Math.pow(1 - r * r, 2) + two * two));
            peak = Math.max(peak, t);
            series.add(new double[]{round(f, 2), round(t, 4)});
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("naturalFrequencyHz", round(fn, 1));
        summary.put("totalMassKg", round(mass, 3));
        summary.put("peakTransmissibility", round(peak, 3));
        summary.put("resonanceInRange", fn >= fMin && fn <= fMax);
        // 전달률 > 1 (증폭) 구간: f < fn·√2
        double ampEdge = fn * Math.sqrt(2);
        summary.put("amplificationBandHz", ampEdge > fMin
                ? Map.of("min", round(fMin, 2), "max", round(Math.min(ampEdge, fMax), 2)) : null);
        return output("Hz", "Transmissibility", series, summary);
    }

    /** 온도 설정값: 시작 → 목표까지 선형 승온(rampMin) 후 유지(holdMin) */
    Map<String, Object> chamber(Map<String, Object> in) {
        double start = between(in, "startTempC", -100, 300);
        double target = between(in, "targetTempC", -100, 300);
        double ramp = positive(in, "rampMin");
        double hold = nonNegative(in, "holdMin");
        double humidity = between(in, "targetHumidityPct", 0, 100);
        List<double[]> series = new ArrayList<>();
        for (double t : linSpace(0, ramp, 20)) {
            series.add(new double[]{round(t, 2), round(start + (target - start) * t / ramp, 2)});
        }
        if (hold > 0) {
            series.add(new double[]{round(ramp + hold, 2), round(target, 2)});
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rampRateCPerMin", round((target - start) / ramp, 3));
        summary.put("totalMin", round(ramp + hold, 2));
        summary.put("targetTempC", round(target, 2));
        summary.put("targetHumidityPct", round(humidity, 1));
        return output("min", "°C", series, summary);
    }

    /** F = k·x */
    Map<String, Object> linearForce(Map<String, Object> in) {
        double k = positive(in, "specimenStiffnessNPerMm");
        double max = positive(in, "maxDisplacementMm");
        List<double[]> series = new ArrayList<>();
        for (double x : linSpace(0, max, 10)) {
            series.add(new double[]{round(x, 3), round(k * x, 3)});
        }
        return output("mm", "N", series, Map.of("maxForceN", round(k * max, 3)));
    }

    /** E = m·g·h,  v = √(2gh) */
    Map<String, Object> impact(Map<String, Object> in) {
        double m = positive(in, "massKg");
        double h = positive(in, "dropHeightM");
        List<double[]> series = new ArrayList<>();
        for (double x : linSpace(0, h, 10)) {
            series.add(new double[]{round(x, 3), round(m * G * x, 3)});
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("energyJ", round(m * G * h, 3));
        summary.put("impactVelocityMps", round(Math.sqrt(2 * G * h), 3));
        return output("m", "J", series, summary);
    }

    /** 정전류: 시간(h) = 용량 × |SOC 변화| / 전류 */
    Map<String, Object> battery(Map<String, Object> in) {
        double cap = positive(in, "capacityAh");
        double current = positive(in, "currentA");
        double soc0 = between(in, "initialSocPct", 0, 100);
        double soc1 = between(in, "finalSocPct", 0, 100);
        if (soc0 == soc1) {
            throw new InvalidInput("finalSocPct", "시작·목표 SOC 가 같습니다.");
        }
        double hours = cap * Math.abs(soc1 - soc0) / 100 / current;
        List<double[]> series = new ArrayList<>();
        for (double t : linSpace(0, hours, 10)) {
            series.add(new double[]{round(t, 3), round(soc0 + (soc1 - soc0) * t / hours, 2)});
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", soc1 > soc0 ? "CHARGE" : "DISCHARGE");
        summary.put("durationHours", round(hours, 3));
        summary.put("durationMinutes", round(hours * 60, 1));
        return output("h", "SOC %", series, summary);
    }

    /** 하중 설정값: 0 → 목표 하중까지 선형 증가(rampSeconds) 후 유지(holdSeconds) */
    Map<String, Object> press(Map<String, Object> in) {
        double force = positive(in, "targetForceKN");
        double ramp = positive(in, "rampSeconds");
        double hold = nonNegative(in, "holdSeconds");
        List<double[]> series = new ArrayList<>();
        for (double t : linSpace(0, ramp, 10)) {
            series.add(new double[]{round(t, 2), round(force * t / ramp, 3)});
        }
        if (hold > 0) {
            series.add(new double[]{round(ramp + hold, 2), round(force, 3)});
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("peakForceKN", round(force, 3));
        summary.put("loadRateKNPerS", round(force / ramp, 3));
        summary.put("totalSeconds", round(ramp + hold, 2));
        return output("s", "kN", series, summary);
    }

    /** I / I0 = e^(−μx) */
    Map<String, Object> xray(Map<String, Object> in) {
        double thickness = positive(in, "thicknessMm");
        double mu = nonNegative(in, "attenuationCoefficientPerMm");
        List<double[]> series = new ArrayList<>();
        for (double x : linSpace(0, thickness, 20)) {
            series.add(new double[]{round(x, 3), round(Math.exp(-mu * x), 4)});
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transmissionRatio", round(Math.exp(-mu * thickness), 4));
        summary.put("halfValueLayerMm", mu > 0 ? round(Math.log(2) / mu, 3) : null);
        return output("mm", "I/I0", series, summary);
    }

    /** dB = 20·log10(음압비) */
    Map<String, Object> sound(Map<String, Object> in) {
        double ratio = positive(in, "amplitudeRatio");
        double lo = Math.min(1, ratio);
        double hi = ratio == 1 ? 10 : Math.max(1, ratio); // 음압비 1(0 dB) 이면 1~10 구간을 보여 줌
        List<double[]> series = new ArrayList<>();
        for (double r : logSpace(lo, hi, 20)) {
            series.add(new double[]{round(r, 4), round(20 * Math.log10(r), 2)});
        }
        return output("음압비", "dB", series, Map.of("decibel", round(20 * Math.log10(ratio), 2)));
    }

    /** P = V·I */
    Map<String, Object> dcPower(Map<String, Object> in) {
        double v = positive(in, "voltageV");
        double i = positive(in, "currentA");
        List<double[]> series = new ArrayList<>();
        for (double x : linSpace(0, i, 10)) {
            series.add(new double[]{round(x, 3), round(v * x, 3)});
        }
        return output("A", "W", series, Map.of("powerW", round(v * i, 3)));
    }

    // ------------------------------------------------------------ RULE_BASED_DEMO

    /** 입력 필드 ↔ 시연 사양 키 (입력 ≤ 사양이면 MET) */
    record Check(String input, String specKey, String label) {
    }

    Map<String, Object> fits(Map<String, Object> in, List<Map<String, Object>> specs, List<Check> checks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Check c : checks) {
            double required = positive(in, c.input());
            Map<String, Object> spec = spec(specs, c.specKey());
            Double available = spec == null ? null : number(spec.get("value"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("input", c.input());
            row.put("specKey", c.specKey());
            row.put("label", c.label());
            row.put("required", round(required, 3));
            row.put("available", available == null ? null : round(available, 3));
            row.put("unit", spec == null ? null : spec.get("unit"));
            row.put("result", available == null ? "UNKNOWN" : required <= available ? "MET" : "NOT_MET");
            rows.add(row);
        }
        return ruleOutput(rows);
    }

    /** 대상 주파수가 시연 주파수 범위(min~max) 안에 있는지 */
    Map<String, Object> rfCoverage(Map<String, Object> in, List<Map<String, Object>> specs) {
        double target = positive(in, "targetFrequencyGHz");
        Map<String, Object> spec = spec(specs, "frequency");
        Double min = spec == null ? null : number(spec.get("min"));
        Double max = spec == null ? null : number(spec.get("max"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("input", "targetFrequencyGHz");
        row.put("specKey", "frequency");
        row.put("label", "주파수 범위");
        row.put("required", round(target, 4));
        row.put("available", min == null || max == null ? null : Map.of("min", min, "max", max));
        row.put("unit", spec == null ? "GHz" : spec.get("unit"));
        row.put("result", min == null || max == null ? "UNKNOWN" : target >= min && target <= max ? "MET" : "NOT_MET");
        return ruleOutput(List.of(row));
    }

    private static Map<String, Object> ruleOutput(List<Map<String, Object>> rows) {
        String verdict = rows.stream().anyMatch(r -> "NOT_MET".equals(r.get("result"))) ? "NOT_MET"
                : rows.stream().allMatch(r -> "MET".equals(r.get("result"))) ? "MET" : "UNKNOWN";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", rows);
        out.put("summary", Map.of("verdict", verdict));
        return out;
    }

    private static Map<String, Object> spec(List<Map<String, Object>> specs, String key) {
        if (specs == null) {
            return null;
        }
        return specs.stream().filter(s -> key.equals(s.get("key"))).findFirst().orElse(null);
    }

    // ------------------------------------------------------------ 도우미

    private static Map<String, Object> output(String xLabel, String yLabel, List<double[]> series,
                                              Map<String, Object> summary) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("xLabel", xLabel);
        chart.put("yLabel", yLabel);
        chart.put("series", series);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chart", chart);
        out.put("summary", summary);
        return out;
    }

    static List<Double> linSpace(double from, double to, int steps) {
        List<Double> xs = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            xs.add(from + (to - from) * i / steps);
        }
        return xs;
    }

    static List<Double> logSpace(double from, double to, int steps) {
        List<Double> xs = new ArrayList<>();
        double a = Math.log10(from);
        double b = Math.log10(to);
        for (int i = 0; i <= steps; i++) {
            xs.add(Math.pow(10, a + (b - a) * i / steps));
        }
        xs.set(0, from);
        xs.set(steps, to);
        return xs;
    }

    /** 모든 출력 숫자가 거치는 곳. 입력이 유한해도 계산 중 넘치면(Infinity/NaN) 400 으로 돌려보냄 */
    static double round(double v, int digits) {
        if (!Double.isFinite(v)) {
            throw new InvalidInput(null, "입력값이 너무 커서 계산할 수 없습니다. 값을 줄여 주세요.");
        }
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double required(Map<String, Object> in, String field) {
        Double v = number(in.get(field));
        if (v == null || v.isNaN() || v.isInfinite()) {
            throw new InvalidInput(field, field + " 값(숫자)이 필요합니다.");
        }
        return v;
    }

    private static double positive(Map<String, Object> in, String field) {
        double v = required(in, field);
        if (v <= 0) {
            throw new InvalidInput(field, field + " 은(는) 0 보다 커야 합니다.");
        }
        return v;
    }

    private static double nonNegative(Map<String, Object> in, String field) {
        double v = required(in, field);
        if (v < 0) {
            throw new InvalidInput(field, field + " 은(는) 0 이상이어야 합니다.");
        }
        return v;
    }

    private static double between(Map<String, Object> in, String field, double min, double max) {
        double v = required(in, field);
        if (v < min || v > max) {
            throw new InvalidInput(field, field + " 은(는) " + min + " ~ " + max + " 범위여야 합니다.");
        }
        return v;
    }

    private static double greaterThan(Map<String, Object> in, String field, double min) {
        double v = required(in, field);
        if (v <= min) {
            throw new InvalidInput(field, field + " 은(는) " + min + " 보다 커야 합니다.");
        }
        return v;
    }
}
