package kr.astra.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/** AI 해석(LLM 호출) 동안 DB 연결을 잡지 않도록 /search 는 트랜잭션 없이 실행한다 */
class SearchServiceTransactionTest {

    @Test
    void 통합_검색은_트랜잭션을_열지_않는다() throws Exception {
        TransactionAttribute attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                SearchService.class.getMethod("search", String.class), SearchService.class);
        assertThat(attribute).isNull();
    }
}
