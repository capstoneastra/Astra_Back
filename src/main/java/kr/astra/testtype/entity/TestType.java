package kr.astra.testtype.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * test_types: 시험유형 (VIBRATION 진동시험 등 15개). keywords 는 자연어 검색용 동의어.
 */
@Getter
@Entity
@Table(name = "test_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestType {

    @Id
    @Column(length = 50)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 100)
    private String nameKo;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> keywords;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
