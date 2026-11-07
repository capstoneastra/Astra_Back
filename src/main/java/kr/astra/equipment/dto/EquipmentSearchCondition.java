package kr.astra.equipment.dto;

import java.util.List;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.EquipmentType;

/**
 * GET /equipment 검색 조건 (api-spec §2). null·빈 값은 조건 없음.
 *
 * @param q            장비명·모델·제조사·시험유형(이름·코드·키워드) 부분 일치
 * @param regions      지역 (여러 개면 OR)
 * @param externalOnly true 면 외부 이용 가능(EXTERNAL_ALLOWED) 장비만
 * @param includeMock  false 면 시연용 가상 데이터가 섞인 장비(is_mock) 제외
 */
public record EquipmentSearchCondition(
        String q,
        List<String> regions,
        Long institutionId,
        EquipmentType equipmentType,
        String testType,
        Availability availability,
        boolean externalOnly,
        boolean includeMock) {
}
