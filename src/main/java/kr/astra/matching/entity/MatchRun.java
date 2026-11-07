package kr.astra.matching.entity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.*;
import kr.astra.requirement.dto.RequirementSnapshot;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Getter @Entity @Table(name = "match_runs") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long requirementId;
    @Column(nullable = false, length = 30) private String algorithmVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private RequirementSnapshot requirementSnapshot;
    private Long createdBy;
    @CreationTimestamp @Column(nullable = false, updatable = false) private OffsetDateTime createdAt;
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL) @OrderBy("rank ASC")
    private List<MatchResult> results = new ArrayList<>();
    public MatchRun(Long requirementId, Long actorId, RequirementSnapshot snapshot) {
        this.requirementId = requirementId; createdBy = actorId; requirementSnapshot = snapshot;
        algorithmVersion = "rule-v1";
    }
}
