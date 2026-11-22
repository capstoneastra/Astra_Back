package kr.astra.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 회의 결정(2026-11)으로 추가된 필수 검사를 한 번에 켜고 끄는 스위치 (astra.rules.strict, 기본 true).
 * <ul>
 *   <li>기관·대학 가입의 국가연구자번호 필수 (꺼도 보낸 값의 형식은 검사)</li>
 *   <li>의뢰 제출·보완 제출의 이용 희망 기간·실사용자·회계 담당자 필수</li>
 *   <li>결과 등록 후에만 완료</li>
 * </ul>
 * 프론트가 새 칸을 만들기 전에 연동할 때만 .env 에 ASTRA_RULES_STRICT=false 를 넣고, 준비되면 다시 켭니다.
 */
@Component
public class RulesProperties {

    private final boolean strict;

    public RulesProperties(@Value("${astra.rules.strict:true}") boolean strict) {
        this.strict = strict;
    }

    public boolean strict() {
        return strict;
    }
}
