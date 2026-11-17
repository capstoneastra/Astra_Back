package kr.astra.requirement.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/** Exact decimal conversion; unknown dimensions never compare as equal. */
public final class UnitConverter {
    private record Unit(String dimension, BigDecimal scale) {}
    private static Unit u(String d, String s) { return new Unit(d, new BigDecimal(s)); }
    private static final Map<String, Unit> UNITS = Map.ofEntries(
            Map.entry("Hz", u("frequency", "1")), Map.entry("kHz", u("frequency", "1000")),
            Map.entry("MHz", u("frequency", "1000000")), Map.entry("GHz", u("frequency", "1000000000")),
            Map.entry("kg", u("mass", "1")), Map.entry("g", u("mass", "0.001")),
            Map.entry("mm", u("length", "1")), Map.entry("cm", u("length", "10")),
            Map.entry("m", u("length", "1000")), Map.entry("µm", u("length", "0.001")),
            Map.entry("um", u("length", "0.001")), Map.entry("N", u("force", "1")),
            Map.entry("kN", u("force", "1000")), Map.entry("kgf", u("force", "9.80665")),
            Map.entry("V", u("voltage", "1")), Map.entry("kV", u("voltage", "1000")),
            Map.entry("mV", u("voltage", "0.001")), Map.entry("A", u("current", "1")),
            Map.entry("mA", u("current", "0.001")));
    /** 사용자·AI 가 쓰는 표기 → 기준 표기. "%" 는 규칙 단위가 %RH 일 때만 습도로 봄 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("℃", "°C"), Map.entry("도", "°C"), Map.entry("°", "°C"), Map.entry("섭씨", "°C"),
            Map.entry("degC", "°C"), Map.entry("C°", "°C"),
            Map.entry("℃/min", "°C/min"), Map.entry("°C/분", "°C/min"), Map.entry("℃/분", "°C/min"),
            Map.entry("도/분", "°C/min"),
            Map.entry("배", "x"), Map.entry("X", "x"), Map.entry("×", "x"),
            Map.entry("킬로그램", "kg"), Map.entry("그램", "g"), Map.entry("밀리미터", "mm"),
            Map.entry("센티미터", "cm"), Map.entry("미터", "m"), Map.entry("마이크로미터", "µm"), Map.entry("μm", "µm"),
            Map.entry("헤르츠", "Hz"), Map.entry("킬로헤르츠", "kHz"), Map.entry("메가헤르츠", "MHz"),
            Map.entry("기가헤르츠", "GHz"), Map.entry("볼트", "V"), Map.entry("킬로볼트", "kV"),
            Map.entry("암페어", "A"), Map.entry("뉴턴", "N"), Map.entry("킬로뉴턴", "kN"), Map.entry("데시벨", "dB"),
            Map.entry("RH", "%RH"), Map.entry("%rh", "%RH"));
    private static final Map<String, String> CASE_INSENSITIVE = new HashMap<>();
    static {
        for (String unit : List.of("Hz", "kHz", "MHz", "GHz", "kg", "kgf", "kN", "kV", "dB", "%RH"))
            CASE_INSENSITIVE.put(unit.toLowerCase(Locale.ROOT), unit);
    }
    private UnitConverter() {}

    /** 별칭을 기준 표기로 바꿉니다. 모르는 단위는 그대로 두어 이후 검증에서 거절되게 합니다. */
    public static String canonical(String unit, String ruleUnit) {
        if (unit == null) return null;
        String u = unit.strip();
        if (u.equals(ruleUnit)) return u;
        if (u.equals("%") && "%RH".equals(ruleUnit)) return "%RH";
        if (ALIASES.containsKey(u)) return ALIASES.get(u);
        if (UNITS.containsKey(u)) return u;
        return CASE_INSENSITIVE.getOrDefault(u.toLowerCase(Locale.ROOT), u);
    }

    /** 기준 단위를 원문에서 찾을 때 허용하는 표기 (기준 표기 포함) */
    public static Set<String> surfaces(String canonicalUnit) {
        Set<String> result = new LinkedHashSet<>();
        if (canonicalUnit == null) return result;
        result.add(canonicalUnit);
        ALIASES.forEach((alias, target) -> { if (target.equals(canonicalUnit)) result.add(alias); });
        if ("%RH".equals(canonicalUnit)) result.add("%");
        return result;
    }

    /** 환산표에 있는 단위인지 (기준 표기 기준) */
    public static boolean convertible(String canonicalUnit) { return canonicalUnit != null && UNITS.containsKey(canonicalUnit); }

    public static BigDecimal convert(BigDecimal value, String from, String to) {
        if (value == null) return null;
        if (Objects.equals(from, to)) return value;
        Unit a = UNITS.get(from == null ? "" : from);
        Unit b = UNITS.get(to == null ? "" : to);
        if (a == null || b == null || !a.dimension.equals(b.dimension))
            throw new IllegalArgumentException("Incompatible units");
        return value.multiply(a.scale).divide(b.scale, MathContext.DECIMAL128);
    }
}
