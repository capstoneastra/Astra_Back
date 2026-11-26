package kr.astra.equipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import kr.astra.equipment.entity.*;
import org.junit.jupiter.api.Test;

/** 시연 서버의 '열진공' 장비처럼 이용료를 안 적고 등록해도 화면에 '협의'가 나와야 함 (V17 규칙) */
class EquipmentFeeDefaultTest {
    static EquipmentFields fields(Integer amount, FeeUnit unit) {
        return new EquipmentFields("열진공", null, null, null, EquipmentType.TEST, "사천", null, null,
                AccessScope.EXTERNAL_ALLOWED, null, amount, unit, null, null, null);
    }

    @Test void registeringWithoutAnyFeeBecomesNegotiable() {
        var e = Equipment.registerByInstitution(1L, 1L, "TMP", fields(null, null), OffsetDateTime.now());
        assertThat(e.getFeeText()).isEqualTo("협의");
        assertThat(e.getFeeUnit()).isEqualTo(FeeUnit.NEGOTIABLE);
        assertThat(e.getFeeVatIncluded()).isNull();
    }

    @Test void registeringWithAmountStillFormatsPrice() {
        var e = Equipment.registerByInstitution(1L, 1L, "TMP", fields(12000, FeeUnit.HOUR), OffsetDateTime.now());
        assertThat(e.getFeeText()).startsWith("12,000원/시간");
    }
}
