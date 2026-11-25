package kr.astra.reservation.service;

import java.util.List;
import java.util.Map;

/**
 * 시연 의뢰의 제목·목적·시료·결과 문구. 예약이 잡힌 장비의 시험유형(첫 번째)에 맞춰 고릅니다.
 * 유형마다 3가지(variant 0~2)를 두어 같은 장비의 일정끼리 제목이 겹치지 않게 합니다.
 * 목록에 없는 시험유형은 장비 이름으로 만든 일반 문구를 씁니다.
 */
final class DemoRequestThemes {

    record Theme(String title, String purpose, String sampleName, String resultSummary) {
    }

    private record Text(String title, String purpose, String sampleName) {
    }

    private record TypeThemes(List<Text> texts, String resultSummary) {
    }

    private static final Map<String, TypeThemes> BY_TEST_TYPE = Map.of(
            "VIBRATION", new TypeThemes(List.of(
                    new Text("위성 전장품 진동시험", "발사 환경 진동 내구성 확인", "위성 탑재 전장품"),
                    new Text("항공 전장 커넥터 진동시험", "비행 진동 환경 접촉 안정성 확인", "항공용 커넥터 모듈"),
                    new Text("드론 비행제어기 연속 진동시험", "장시간 진동 환경 동작 확인", "비행제어기 보드")),
                    "가진 조건 5~2,000Hz, 3축 각 30분 진동 후 외관·접촉저항 이상 없음. 시험 후 기능 점검 정상."),
            "TEMPERATURE_HUMIDITY", new TypeThemes(List.of(
                    new Text("항공 전장 커넥터 온습도 시험", "고온·고습 환경 접촉 안정성 확인", "항공용 커넥터 모듈"),
                    new Text("무인기 배터리팩 온도 사이클 시험", "저온·고온 반복 환경 동작 안정성 확인", "무인기 배터리팩"),
                    new Text("위성 태양전지판 온습도 내구시험", "온습도 사이클 후 출력 저하 확인", "태양전지판 시편")),
                    "-40~85℃, 85%RH 조건 10사이클 후 외관 변형·부식 없음. 시험 전후 전기 특성 변화 기준 이내."),
            "X_RAY_INSPECTION", new TypeThemes(List.of(
                    new Text("위성 전장품 내부 결함 X선 검사", "납땜·내부 공극 결함 확인", "위성 탑재 전장품"),
                    new Text("탑재체 구조물 X선 비파괴검사", "용접부 내부 균열 확인", "탑재체 구조 시편"),
                    new Text("발사체 브래킷 3D X선 검사", "적층 제조 부품 내부 기공 확인", "발사체 브래킷")),
                    "3D CT 촬영 결과 기준 크기 이상의 균열·공극 없음. 단면 이미지는 성적서에 첨부."),
            "ELECTRICAL_RF_ANALYSIS", new TypeThemes(List.of(
                    new Text("소형 위성 통신 소자 특성 분석", "고주파 소자 전기적 특성 확인", "큐브위성 통신 소자"),
                    new Text("무인기 전력 소자 특성 분석", "전력 반도체 누설전류·내압 확인", "무인기 전력 소자"),
                    new Text("위성 안테나 RF 특성 측정", "동작 대역 반사 손실·이득 확인", "위성 패치 안테나")),
                    "측정 항목 모두 규격 범위 이내. 주요 측정 그래프와 수치는 성적서 참고."));

    private DemoRequestThemes() {
    }

    /** 시험유형 코드(없으면 null)와 장비 이름으로 variant 번째 문구 */
    static Theme of(String testTypeCode, String equipmentName, int variant) {
        TypeThemes themes = testTypeCode == null ? null : BY_TEST_TYPE.get(testTypeCode);
        if (themes == null) {
            String[] fallback = {"부품 성능 시험", "시제품 신뢰성 시험", "양산품 품질 확인 시험"};
            String title = equipmentName + " " + fallback[Math.floorMod(variant, fallback.length)];
            return new Theme(title, "시험 조건 적합성 확인", "시험 시편",
                    "의뢰한 시험 조건으로 시험을 마쳤고 판정 기준을 모두 충족함. 상세 수치는 성적서 참고.");
        }
        Text t = themes.texts().get(Math.floorMod(variant, themes.texts().size()));
        return new Theme(t.title(), t.purpose(), t.sampleName(), themes.resultSummary());
    }
}
