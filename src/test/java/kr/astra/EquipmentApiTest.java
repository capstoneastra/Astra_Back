package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.astra.TestApi.Res;
import kr.astra.equipment.dto.EquipmentCandidate;
import kr.astra.equipment.dto.EquipmentSpecView;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.service.EquipmentQueryService;
import kr.astra.global.security.JwtProvider;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * 장비 목록·검색·상세, 시험유형, 기관 (api-spec §2) 과 BE-C 용 EquipmentQueryService.
 * 기대값은 V2 seed 실제 데이터 (진동시험 장비: ASTRA-CAND-0044·0045·0047·0048).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EquipmentApiTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    EquipmentQueryService equipmentQueryService;

    @Autowired
    TestTypeService testTypeService;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private TestApi api;
    private String token;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, null);
        token = jwtProvider.createAccessToken(1L, UserRole.COMPANY_USER, null);
    }

    // ------------------------------------------------------------ GET /equipment

    @Test
    void 사양사전과_RF_기준단위와_원문단위를_조회한다() throws Exception {
        Res dictionary = api.get("/api/v1/equipment/spec-definitions",token);
        assertThat(dictionary.status()).isEqualTo(200);
        assertThat(dictionary.data().size()).isEqualTo(29);
        var rf = api.get("/api/v1/equipment/37",token).data().path("specs").get(0);
        assertThat(rf.path("unit").asString()).isEqualTo("Hz");
        assertThat(rf.path("originalUnit").asString()).isEqualTo("GHz");
        assertThat(rf.path("min").asLong()).isEqualTo(75_000_000_000L);
        assertThat(rf.path("max").asLong()).isEqualTo(110_000_000_000L);
    }

    @Test
    void 정렬값이_같으면_이용_가능한_장비가_먼저_나온다() throws Exception {
        java.util.List<String> order = java.util.List.of("AVAILABLE", "BUSY", "MAINTENANCE", "UNAVAILABLE", "UNKNOWN");
        JsonNode content = api.get("/api/v1/equipment?includeMock=true&sort=infoUpdatedAt,desc&size=100", token)
                .data().path("content");
        assertThat(content.size()).isPositive();
        for (int i = 1; i < content.size(); i++) {
            JsonNode prev = content.get(i - 1);
            JsonNode cur = content.get(i);
            if (prev.path("infoUpdatedAt").asString().equals(cur.path("infoUpdatedAt").asString())) {
                assertThat(order.indexOf(cur.path("availability").asString()))
                        .isGreaterThanOrEqualTo(order.indexOf(prev.path("availability").asString()));
            }
        }
        // 첫 화면이 '미확인' 장비로 시작하지 않음
        assertThat(content.get(0).path("availability").asString()).isEqualTo("AVAILABLE");
    }

    @Test
    void 공인필터는_시험유형과_유효기간을_같이_확인한다() throws Exception {
        assertThat(api.get("/api/v1/equipment?accreditedOnly=true",token).data().path("totalElements").asLong()).isZero();
        try {
            jdbc.update("UPDATE equipment_test_types SET is_accredited=true,accreditation_no='TEST-ONLY',"
                    + "accreditation_valid_until=(now() AT TIME ZONE 'Asia/Seoul')::date WHERE equipment_id=48 AND test_type_code='VIBRATION'");
            assertThat(externalIds(api.get("/api/v1/equipment?accreditedOnly=true&testType=VIBRATION",token)))
                    .containsExactly("ASTRA-CAND-0048");
            assertThat(api.get("/api/v1/equipment?accreditedOnly=true&testType=TEMPERATURE_HUMIDITY",token)
                    .data().path("totalElements").asLong()).isZero();
            jdbc.update("UPDATE equipment_test_types SET accreditation_valid_until=accreditation_valid_until-1 WHERE equipment_id=48");
            assertThat(api.get("/api/v1/equipment?accreditedOnly=true",token).data().path("totalElements").asLong()).isZero();
        } finally {
            jdbc.update("UPDATE equipment_test_types SET is_accredited=NULL,accreditation_no=NULL,accreditation_valid_until=NULL WHERE equipment_id=48");
        }
    }

    @Test
    void 기관_정비일정이_공개_가용성에_반영되고_다른기관은_바꿀수없다() throws Exception {
        String owner = jwtProvider.createAccessToken(3L,UserRole.INSTITUTION_MANAGER,2L);
        String outsider = jwtProvider.createAccessToken(4L,UserRole.INSTITUTION_MANAGER,3L);
        String url = "/api/v1/equipment/48/availability?from=2030-01-02&to=2030-01-02";
        assertThat(api.get(url,token).data().path("available").asBoolean()).isFalse();
        String body = """
                {"startAt":"2030-01-02T10:00:00+09:00","endAt":"2030-01-02T12:00:00+09:00","reason":"통합 테스트 정비"}
                """;
        assertThat(api.post("/api/v1/equipment/48/downtimes",body,outsider).status()).isEqualTo(403);
        Res added = api.post("/api/v1/equipment/48/downtimes",body,owner);
        assertThat(added.status()).isEqualTo(201);
        long downtimeId = added.data().path("id").asLong();
        try {
            var calendar = api.get(url,token).data();
            assertThat(calendar.path("needsConfirmation").asBoolean()).isTrue();
            assertThat(calendar.path("intervals").get(1).path("availability").asString()).isEqualTo("MAINTENANCE");
            assertThat(calendar.toString()).doesNotContain("통합 테스트 정비", "requestId", "requesterId");
            assertThat(api.send("DELETE","/api/v1/equipment/48/downtimes/"+downtimeId,null,outsider).status()).isEqualTo(403);
            assertThat(api.send("DELETE","/api/v1/equipment/48/downtimes/"+downtimeId,null,owner).status()).isEqualTo(200);
        } finally {
            jdbc.update("DELETE FROM equipment_downtimes WHERE id=?",downtimeId);
        }
    }

    @Test
    void 정비와_이용불가_장비는_검색에는_남고_매칭후보에서_빠진다() throws Exception {
        for (long id : List.of(50L,51L)) {
            assertThat(api.get("/api/v1/equipment/"+id,token).status()).isEqualTo(200);
            for (String code : jdbc.queryForList("SELECT test_type_code FROM equipment_test_types WHERE equipment_id=?",String.class,id)) {
                assertThat(equipmentQueryService.findCandidates(code,null)).extracting(EquipmentCandidate::id).doesNotContain(id);
            }
        }
    }

    @Test
    void 이용료_문구는_금액원_단위_한_형식이고_시연_장비는_가동률이_있다() throws Exception {
        // V17: "12,000원/시간 (부가세 별도)" 또는 "협의" 만 있음 (KRW·hour 같은 원문 형식 없음)
        assertThat(jdbc.queryForList("""
                SELECT external_id FROM equipment
                WHERE fee_text IS NULL
                   OR fee_text !~ '^([0-9]{1,3}(,[0-9]{3})*원/(시간|일|시료|건) [(]부가세 (별도|포함)[)]|협의)$'
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM equipment
                WHERE (fee_text = '협의') <> (fee_amount IS NULL AND fee_unit = 'NEGOTIABLE' AND fee_vat_included IS NULL)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment WHERE is_mock AND utilization_rate IS NULL",
                Long.class)).isZero();

        JsonNode usage = api.get("/api/v1/equipment/32", token).data().path("usage");
        assertThat(usage.path("feeText").asString()).isEqualTo("12,000원/시간 (부가세 별도)");
        assertThat(usage.path("feeAmount").asInt()).isEqualTo(12000);
        assertThat(usage.path("feeUnit").asString()).isEqualTo("HOUR");
        assertThat(usage.path("vatIncluded").asBoolean(true)).isFalse();
    }

    @Test
    void 장비_목록_항목은_api_spec_예시와_같은_형식이다() throws Exception {
        Res res = api.get("/api/v1/equipment?testType=VIBRATION", token);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.data().path("totalElements").asLong()).isEqualTo(activeWithTestType("VIBRATION"));
        assertThat(externalIds(res).subList(0, 4)).containsExactly("ASTRA-CAND-0044", "ASTRA-CAND-0045",
                "ASTRA-CAND-0047", "ASTRA-CAND-0048");

        JsonNode e = find(res.data().path("content"), "ASTRA-CAND-0048");
        assertThat(e.propertyNames()).containsExactlyInAnyOrder("id", "externalId", "name", "model", "institution",
                "region", "equipmentType", "testTypes", "availability", "utilizationRate", "feeText",
                "verificationStatus", "isMock", "infoUpdatedAt", "availabilityNeedsConfirmation",
                "usage", "keySpecs", "simulation", "specs");
        assertThat(e.path("id").asLong()).isEqualTo(48);
        assertThat(e.path("name").asString()).isEqualTo("진동시험기");
        assertThat(e.path("model").asString()).isEqualTo("EDS5000LS4-445");
        assertThat(e.path("institution").path("id").asLong()).isEqualTo(2);
        assertThat(e.path("institution").path("name").asString()).isEqualTo("미리내우주환경시험센터");
        assertThat(e.path("region").asString()).isEqualTo("대전");
        assertThat(e.path("equipmentType").asString()).isEqualTo("TEST");
        assertThat(e.path("testTypes").toString()).isEqualTo("[\"VIBRATION\"]");
        assertThat(e.path("availability").asString()).isEqualTo("UNKNOWN");
        // V17: 시연 장비 가동률 채움, 금액 없는 장비는 '협의'
        assertThat(e.path("utilizationRate").asDouble()).isBetween(25.0, 84.0);
        assertThat(e.path("feeText").asString()).isEqualTo("협의");
        assertThat(e.path("usage").path("feeText").asString()).isEqualTo("협의");
        assertThat(e.path("usage").path("feeUnit").asString()).isEqualTo("NEGOTIABLE");
        assertThat(e.path("usage").path("feeAmount").isNull()).isTrue();
        assertThat(e.path("usage").path("vatIncluded").isNull()).isTrue();
        assertThat(e.path("usage").has("accessScope")).isTrue();
        // 주요 사양: 상세 specs 순서에서 키가 다른 앞 2개, 프리셋 id 는 상세와 같음
        JsonNode detail = api.get("/api/v1/equipment/48", token).data();
        assertThat(e.path("keySpecs").size()).isEqualTo(2);
        assertThat(e.path("keySpecs").get(0)).isEqualTo(detail.path("specs").get(0));
        assertThat(e.path("keySpecs").get(1).path("key").asString())
                .isNotEqualTo(e.path("keySpecs").get(0).path("key").asString());
        // 사양 전체: 상세 specs 와 같은 값·순서 (프론트 목록이 specs 를 읽음)
        assertThat(e.path("specs")).isEqualTo(detail.path("specs"));
        assertThat(e.path("simulation")).isEqualTo(detail.path("simulation"));
        assertThat(e.path("simulation").path("presetIds").size()).isPositive();
        assertThat(e.path("verificationStatus").asString()).isEqualTo("LISTING_CONFIRMED");
        assertThat(e.path("isMock").asBoolean()).isTrue();
        assertThat(e.path("infoUpdatedAt").isNull()).isFalse();
    }

    @Test
    void 기본_목록은_활성_장비를_외부_id_순으로_20개씩_준다() throws Exception {
        long total = jdbc.queryForObject("SELECT count(*) FROM equipment WHERE is_active", Long.class);
        Res res = api.get("/api/v1/equipment", token);
        assertThat(res.data().path("totalElements").asLong()).isEqualTo(total);
        assertThat(res.data().path("size").asInt()).isEqualTo(20);
        assertThat(res.data().path("totalPages").asInt()).isEqualTo((int) ((total + 19) / 20));
        assertThat(externalIds(res).getFirst()).isEqualTo("ASTRA-CAND-0001");

        int lastPage = (int) ((total + 19) / 20) - 1;
        Res last = api.get("/api/v1/equipment?page=" + lastPage, token);
        assertThat(last.data().path("content").size()).isEqualTo((int) (total - lastPage * 20L));
    }

    @Test
    void 검색어는_장비명과_시험유형_키워드로_찾는다() throws Exception {
        assertThat(externalIds(api.get("/api/v1/equipment?q=" + enc("진동") + "&size=100", token)))
                .contains("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0047", "ASTRA-CAND-0048");
        // 시험유형 키워드(sine)로도 진동시험 장비가 나옴
        assertThat(externalIds(api.get("/api/v1/equipment?q=sine&size=100", token)))
                .contains("ASTRA-CAND-0048");
        // LIKE 특수문자는 글자 그대로 (오류 없이 0건)
        Res special = api.get("/api/v1/equipment?q=" + enc("%_%"), token);
        assertThat(special.status()).isEqualTo(200);
        assertThat(special.data().path("totalElements").asLong()).isZero();
    }

    @Test
    void 지역_기관_유형_외부이용_MOCK_필터() throws Exception {
        Res regions = api.get("/api/v1/equipment?region=" + enc("진주") + "&region=" + enc("경남") + "&size=100", token);
        // 진주 5 + V5 로 채운 진주뿌리기술지원센터 31 + 경남 1, V22 에서 소프트웨어 4종 비활성
        assertThat(regions.data().path("totalElements").asLong()).isEqualTo(33);
        regions.data().path("content").forEach(n -> assertThat(n.path("region").asString()).isIn("진주", "경남"));

        Res inst = api.get("/api/v1/equipment?institutionId=2&equipmentType=TEST", token);
        assertThat(inst.data().path("totalElements").asLong()).isEqualTo(2);

        Res external = api.get("/api/v1/equipment?externalOnly=true", token);
        assertThat(external.data().path("totalElements").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM equipment WHERE is_active AND access_scope = 'EXTERNAL_ALLOWED'", Long.class))
                .isPositive();

        // seed 장비는 모두 시연용(V8). 회원이 직접 등록한 장비만 실제 데이터로 남음
        Res noMock = api.get("/api/v1/equipment?includeMock=false&size=100", token);
        assertThat(noMock.data().path("totalElements").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM equipment WHERE is_active AND NOT is_mock", Long.class));
        assertThat(externalIds(noMock)).noneMatch(id -> id.startsWith("ASTRA-CAND-") || id.startsWith("ASTRA-DEMO-"));
    }

    @Test
    void 정렬은_허용된_필드만_되고_잘못된_값은_400() throws Exception {
        assertThat(api.get("/api/v1/equipment?sort=infoUpdatedAt,desc", token).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/equipment?sort=name", token).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/api/v1/equipment?equipmentType=NOPE", token).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/api/v1/equipment", null).code()).isEqualTo("AUTH_REQUIRED");
    }

    // ------------------------------------------------------------ GET /equipment/{id}

    @Test
    void 장비_상세는_이용조건_사양_출처_시뮬레이션을_준다() throws Exception {
        Res res = api.get("/api/v1/equipment/48", token);
        assertThat(res.status()).isEqualTo(200);
        JsonNode d = res.data();
        assertThat(d.path("externalId").asString()).isEqualTo("ASTRA-CAND-0048");
        assertThat(d.path("manufacturer").asString()).isEqualTo("(주)팜테크");
        assertThat(d.path("usage").path("accessScope").asString()).isEqualTo("UNKNOWN");
        assertThat(d.path("source").path("type").asString()).isEqualTo("WEB");
        assertThat(d.path("simulation").path("capability").asString()).isEqualTo("CALCULATED_DEMO");
        assertThat(d.path("simulation").path("presetIds").toString()).isEqualTo("[\"SIM-0048\"]");

        JsonNode specs = d.path("specs");
        assertThat(specs.size()).isEqualTo(4);
        JsonNode frequency = specs.get(0);
        assertThat(frequency.path("key").asString()).isEqualTo("frequency");
        assertThat(frequency.path("label").asString()).isEqualTo("주파수 범위");
        assertThat(frequency.path("min").asInt()).isEqualTo(10);
        assertThat(frequency.path("max").asInt()).isEqualTo(3000);
        assertThat(frequency.path("unit").asString()).isEqualTo("Hz");
        assertThat(frequency.has("value")).isFalse();
        assertThat(frequency.path("sourceType").asString()).isEqualTo("MOCK");
        assertThat(frequency.path("usageScope").asString()).isEqualTo("DEMO_ONLY");
        assertThat(frequency.path("verificationStatus").asString()).isEqualTo("NOT_VERIFIED");
        assertThat(specs.get(1).path("key").asString()).isEqualTo("max_payload");
        assertThat(specs.get(1).path("value").asInt()).isEqualTo(40);
    }

    @Test
    void 같은_사양_키에_REAL과_MOCK이_있으면_둘_다_주고_REAL이_먼저다() throws Exception {
        JsonNode specs = api.get("/api/v1/equipment/37", token).data().path("specs");
        assertThat(specs.get(0).path("key").asString()).isEqualTo("frequency");
        assertThat(specs.get(0).path("sourceType").asString()).isEqualTo("REAL");
        assertThat(specs.get(1).path("key").asString()).isEqualTo("frequency");
        assertThat(specs.get(1).path("sourceType").asString()).isEqualTo("MOCK");
    }

    @Test
    void 없는_장비는_404_EQUIPMENT_NOT_FOUND() throws Exception {
        Res res = api.get("/api/v1/equipment/999999", token);
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.code()).isEqualTo("EQUIPMENT_NOT_FOUND");
    }

    // ------------------------------------------------------------ 시험유형

    @Test
    void 시험유형_목록은_20개를_표시순서로_장비_수와_함께_준다() throws Exception {
        JsonNode list = api.get("/api/v1/test-types", token).data();
        assertThat(list.size()).isEqualTo(20);
        assertThat(list.get(0).path("code").asString()).isEqualTo("ACOUSTIC_NOISE");
        JsonNode vibration = findBy(list, "code", "VIBRATION");
        assertThat(vibration.path("nameKo").asString()).isEqualTo("진동시험");
        assertThat(vibration.path("equipmentCount").asLong()).isEqualTo(activeWithTestType("VIBRATION"));
        assertThat(vibration.path("keywords").toString()).contains("진동", "sine");
    }

    @Test
    void 시험_정보_안내는_조건_규칙과_가능_기관을_준다() throws Exception {
        Res res = api.get("/api/v1/test-types/VIBRATION", token);
        assertThat(res.status()).isEqualTo(200);
        JsonNode rules = res.data().path("conditionRules");
        List<String> keys = new ArrayList<>();
        rules.forEach(r -> keys.add(r.path("conditionKey").asString()));
        assertThat(keys).containsExactly("sample_mass", "fixture_mass", "frequency", "excitation_axis", "accredited_required");
        JsonNode mass = rules.get(0);
        assertThat(mass.path("label").asString()).isEqualTo("시료 질량");
        assertThat(mass.path("valueType").asString()).isEqualTo("NUMBER");
        assertThat(mass.path("unit").asString()).isEqualTo("kg");
        assertThat(mass.path("isRequired").asBoolean()).isTrue();
        assertThat(mass.path("specKey").asString()).isEqualTo("max_payload");
        assertThat(mass.path("comparator").asString()).isEqualTo("MAX_LIMIT");
        assertThat(rules.get(3).path("specKey").isNull()).isTrue();
        assertThat(rules.get(3).path("comparator").asString()).isEqualTo("NONE");

        // 장비 수 많은 순, 같으면 가나다순
        JsonNode institutions = res.data().path("institutions");
        // 기관·대학·기업 모두 장비 보유 조직으로 나옴 (V8 시연 장비 포함 9곳)
        assertThat(institutions.size()).isEqualTo(9);
        assertThat(institutions.get(0).path("name").asString()).isEqualTo("늘해랑대학교 항공우주공동기기원");
        assertThat(institutions.get(0).path("equipmentCount").asLong()).isEqualTo(6);
        assertThat(institutions.get(1).path("name").asString()).isEqualTo("다솜대학교 공동실험실습관");
        List<String> names = new ArrayList<>();
        institutions.forEach(i -> names.add(i.path("name").asString()));
        assertThat(names).contains("다온신뢰성시험센터", "마루기술지원센터", "미리내우주환경시험센터", "미르항공정밀");
        JsonNode daon = findBy(institutions, "name", "다온신뢰성시험센터");
        assertThat(daon.path("equipmentCount").asLong()).isEqualTo(2);
        assertThat(daon.path("accessScope").asString()).isEqualTo("UNKNOWN");
        assertThat(daon.path("estCostMin").isNull()).isTrue();

        assertThat(api.get("/api/v1/test-types/NOPE", token).code()).isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------------------ 기관

    @Test
    void 기관_목록은_승인된_기관을_가나다순으로_장비_수와_함께_준다() throws Exception {
        JsonNode list = api.get("/api/v1/institutions", token).data();
        // 기관·대학 12곳 + 장비가 있는 기업 3곳 (V8). 테스트 중 장비를 등록한 기업이 있으면 더 늘 수 있음
        assertThat(list.size()).isGreaterThanOrEqualTo(15);
        List<String> names = new ArrayList<>();
        long total = 0;
        for (JsonNode n : list) {
            names.add(n.path("name").asString());
            total += n.path("equipmentCount").asLong();
        }
        assertThat(names).isSortedAccordingTo(Collator.getInstance(Locale.KOREAN));
        assertThat(total).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM equipment WHERE is_active", Long.class));
        assertThat(findBy(list, "name", "미르항공정밀").path("orgType").asString()).isEqualTo("COMPANY");
        assertThat(findBy(list, "name", "가온대학교 첨단소재분석센터").path("orgType").asString()).isEqualTo("UNIVERSITY");
        JsonNode jinju = findBy(list, "name", "한빛뿌리기술지원센터");
        assertThat(jinju.path("region").asString()).isEqualTo("진주");
        assertThat(jinju.path("equipmentCount").asLong()).isEqualTo(27); // V22 소프트웨어 4종 비활성
    }

    @Test
    void 기관_상세는_장비_유형별_수와_가능_시험유형을_준다() throws Exception {
        JsonNode d = api.get("/api/v1/institutions/2", token).data();
        assertThat(d.path("name").asString()).isEqualTo("미리내우주환경시험센터");
        assertThat(d.path("equipmentCount").asLong()).isEqualTo(13);
        assertThat(d.path("equipmentByType").path("MEASUREMENT").asLong()).isEqualTo(11);
        assertThat(d.path("equipmentByType").path("TEST").asLong()).isEqualTo(2);
        JsonNode vibration = findBy(d.path("testTypes"), "code", "VIBRATION");
        assertThat(vibration.path("nameKo").asString()).isEqualTo("진동시험");
        assertThat(vibration.path("equipmentCount").asLong()).isEqualTo(1);

        assertThat(api.get("/api/v1/institutions/999999", token).code()).isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------------------ 유사·중복·비교 (기능 12)

    @Test
    void 유사_장비는_같은_시험유형_장비를_먼저_이유와_함께_준다() throws Exception {
        Res res = api.get("/api/v1/equipment/48/similar", token);
        assertThat(res.status()).isEqualTo(200);
        JsonNode list = res.data();
        assertThat(list.size()).isLessThanOrEqualTo(10);
        List<String> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.path("equipment").path("externalId").asString()));
        assertThat(ids).doesNotContain("ASTRA-CAND-0048");
        // 같은 시험유형(진동시험) 장비가 먼저 나옴
        assertThat(ids.subList(0, 3)).allMatch(id -> hasTestType(id, "VIBRATION"));
        assertThat(list.get(0).path("reasons").toString()).contains("같은 시험유형: 진동시험");
        assertThat(api.get("/api/v1/equipment/999999/similar", token).code()).isEqualTo("EQUIPMENT_NOT_FOUND");
    }

    @Test
    void 중복_장비는_같은_지역_같은_시험유형_2대_이상을_묶는다() throws Exception {
        JsonNode groups = api.get("/api/v1/equipment/duplicates?region=" + enc("대전") + "&testType=VIBRATION", token)
                .data();
        assertThat(groups.size()).isEqualTo(1);
        JsonNode g = groups.get(0);
        assertThat(g.path("region").asString()).isEqualTo("대전");
        assertThat(g.path("testType").path("nameKo").asString()).isEqualTo("진동시험");
        assertThat(g.path("equipmentCount").asInt()).isEqualTo(3);
        assertThat(g.path("institutionCount").asInt()).isEqualTo(2);
        List<String> ids = new ArrayList<>();
        g.path("equipment").forEach(n -> ids.add(n.path("externalId").asString()));
        assertThat(ids).containsExactly("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0048");

        JsonNode all = api.get("/api/v1/equipment/duplicates", token).data();
        assertThat(all.size()).isGreaterThan(1);
        all.forEach(n -> assertThat(n.path("equipmentCount").asInt()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void 비교표는_사양_키별로_장비마다_REAL_우선_값을_준다() throws Exception {
        Res res = api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48, 44] }", token);
        assertThat(res.status()).isEqualTo(200);
        JsonNode d = res.data();
        assertThat(d.path("equipment").get(0).path("id").asLong()).isEqualTo(48);
        assertThat(d.path("usedMockData").asBoolean()).isTrue();
        JsonNode frequency = d.path("rows").get(0);
        assertThat(frequency.path("key").asString()).isEqualTo("frequency");
        assertThat(frequency.path("values").get(0).path("equipmentId").asLong()).isEqualTo(48);
        assertThat(frequency.path("values").get(0).path("max").asInt()).isEqualTo(3000);
        assertThat(frequency.path("values").get(0).path("sourceType").asString()).isEqualTo("MOCK");
        assertThat(frequency.path("values").size()).isEqualTo(2);

        // 장비 37 은 frequency 가 REAL·MOCK 둘 다 있음 → REAL 사용
        JsonNode with37 = api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [37, 48] }", token).data();
        assertThat(with37.path("rows").get(0).path("values").get(0).path("sourceType").asString()).isEqualTo("REAL");

        assertThat(api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48] }", token).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48, 48] }", token).code())
                .isEqualTo("VALIDATION_FAILED");
        Res missing = api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48, 999999] }", token);
        assertThat(missing.code()).isEqualTo("EQUIPMENT_NOT_FOUND");
        assertThat(missing.details().path("equipmentIds").toString()).isEqualTo("[999999]");
    }

    @Test
    void 비교표는_장비별_이용_조건과_시험유형_예상_비용을_함께_준다() throws Exception {
        JsonNode plain = api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48, 44] }", token).data();
        JsonNode usage = plain.path("usage");
        assertThat(usage.size()).isEqualTo(2);
        assertThat(usage.get(0).path("equipmentId").asLong()).isEqualTo(48);
        assertThat(usage.get(0).path("availability").asString()).isEqualTo("UNKNOWN");
        assertThat(usage.get(0).path("accessScope").asString()).isEqualTo("UNKNOWN");
        assertThat(usage.get(0).path("feeAmount").isNull()).isTrue(); // seed 는 요금 정보 없음 → 화면에 "정보 없음"
        assertThat(usage.get(0).path("infoUpdatedAt").isNull()).isFalse();
        assertThat(usage.get(0).path("testTypeEstimate").isNull()).isTrue(); // testType 을 안 보냄
        assertThat(plain.path("testType").isNull()).isTrue();

        // 진동시험 기준: 48 은 가능(예상 비용 정보는 seed 에 없음), 37(전기·RF)은 진동시험 불가
        JsonNode byType = api.post("/api/v1/equipment/compare",
                "{ \"equipmentIds\": [48, 37], \"testType\": \"VIBRATION\" }", token).data();
        assertThat(byType.path("testType").asString()).isEqualTo("VIBRATION");
        JsonNode e48 = byType.path("usage").get(0).path("testTypeEstimate");
        assertThat(e48.path("code").asString()).isEqualTo("VIBRATION");
        assertThat(e48.path("supported").asBoolean()).isTrue();
        assertThat(e48.path("estCostMin").isNull()).isTrue();
        assertThat(byType.path("usage").get(1).path("testTypeEstimate").path("supported").asBoolean()).isFalse();

        Res unknown = api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48, 44], \"testType\": \"NOPE\" }", token);
        assertThat(unknown.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(unknown.details().path("fields").get(0).path("field").asString()).isEqualTo("testType");
    }

    // ------------------------------------------------------------ BE-C 용 내부 인터페이스

    @Test
    void 매칭_후보와_결과_요약에_이용_범위가_들어간다_공동활용_단독활용() {
        // 회의 결정: 추천할 때 공동활용(EXTERNAL_ALLOWED) / 단독활용(INTERNAL_ONLY) 을 같이 보여 줌
        String original44 = jdbc.queryForObject("SELECT access_scope FROM equipment WHERE id = 44", String.class);
        jdbc.update("UPDATE equipment SET access_scope = 'INTERNAL_ONLY' WHERE id = 44");
        try {
            String scope48 = jdbc.queryForObject("SELECT access_scope FROM equipment WHERE id = 48", String.class);
            var summaries = equipmentQueryService.findSummaries(List.of(44L, 48L));
            assertThat(summaries.get(44L).accessScope().name()).isEqualTo("INTERNAL_ONLY");
            assertThat(summaries.get(48L).accessScope().name()).isEqualTo(scope48);
            assertThat(equipmentQueryService.findCandidates("VIBRATION", null)).filteredOn(c -> c.id() == 44L)
                    .singleElement().satisfies(c -> assertThat(c.accessScope().name()).isEqualTo("INTERNAL_ONLY"));
        } finally {
            jdbc.update("UPDATE equipment SET access_scope = ? WHERE id = 44", original44);
        }
    }

    @Test
    void 매칭_후보와_사양을_조회한다() {
        List<EquipmentCandidate> all = equipmentQueryService.findCandidates("VIBRATION", null);
        // 정비 중·이용 불가 장비는 후보에서 빠짐
        assertThat(all).hasSize(jdbc.queryForObject("SELECT count(*) FROM equipment e JOIN equipment_test_types t "
                + "ON t.equipment_id = e.id WHERE e.is_active AND t.test_type_code = 'VIBRATION' "
                + "AND e.availability NOT IN ('UNAVAILABLE', 'MAINTENANCE')", Integer.class));
        assertThat(all).extracting(EquipmentCandidate::externalId)
                .startsWith("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0047", "ASTRA-CAND-0048");
        EquipmentCandidate c48 = all.get(3);
        assertThat(c48.institutionName()).isEqualTo("미리내우주환경시험센터");
        assertThat(c48.isMock()).isTrue();
        assertThat(equipmentQueryService.findCandidates("VIBRATION", "대전")).hasSize(3);

        List<EquipmentSpecView> specs = equipmentQueryService.getSpecs(48L);
        assertThat(specs).extracting(EquipmentSpecView::specKey)
                .containsExactly("frequency", "max_payload", "max_fixture_mass", "rated_sine_force");
        assertThat(specs).allMatch(s -> s.sourceType() == SpecSourceType.MOCK);
        assertThat(equipmentQueryService.getSpecs(999999L)).isEmpty();

        assertThat(testTypeService.getConditionRules("VIBRATION")).hasSize(5);
    }

    // ------------------------------------------------------------ helpers

    /** 활성 장비 중 시험유형이 연결된 것 (seed 가 바뀌어도 DB 기준으로 비교) */
    private long activeWithTestType(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM equipment e JOIN equipment_test_types t ON t.equipment_id = e.id "
                + "WHERE e.is_active AND t.test_type_code = ?", Long.class, code);
    }

    private boolean hasTestType(String externalId, String code) {
        return jdbc.queryForObject("SELECT count(*) FROM equipment e JOIN equipment_test_types t ON t.equipment_id = e.id "
                + "WHERE e.external_id = ? AND t.test_type_code = ?", Long.class, externalId, code) > 0;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> externalIds(Res res) {
        List<String> ids = new ArrayList<>();
        res.data().path("content").forEach(n -> ids.add(n.path("externalId").asString()));
        return ids;
    }

    private static JsonNode find(JsonNode content, String externalId) {
        return findBy(content, "externalId", externalId);
    }

    private static JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " 항목이 없습니다: " + array);
    }
}
