package kr.astra.requirement.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Objects;

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
    private UnitConverter() {}
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
