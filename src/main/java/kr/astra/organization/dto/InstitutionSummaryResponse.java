package kr.astra.organization.dto;

/** GET /institutions 항목 (승인된 기관, 이름 가나다순). equipmentCount = 활성 장비 수 */
public record InstitutionSummaryResponse(Long id, String name, String parentName, String region,
                                         long equipmentCount) {

    public static InstitutionSummaryResponse of(InstitutionInfo info, long equipmentCount) {
        return new InstitutionSummaryResponse(info.id(), info.name(), info.parentName(), info.region(),
                equipmentCount);
    }
}
