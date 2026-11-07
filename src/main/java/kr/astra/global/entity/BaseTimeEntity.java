package kr.astra.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.OffsetDateTime;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * created_at / updated_at 컬럼이 둘 다 있는 테이블의 Entity 가 상속합니다.
 * (created_at 만 있는 테이블은 상속하지 말고 필드를 직접 선언하세요. ddl-auto=validate 가 컬럼 불일치를 잡습니다.)
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
