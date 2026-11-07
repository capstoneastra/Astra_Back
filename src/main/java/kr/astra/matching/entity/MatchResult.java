package kr.astra.matching.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import kr.astra.matching.dto.MatchResponse;
import lombok.*;
@Getter @Entity @Table(name = "match_results") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "match_run_id", nullable = false) private MatchRun run;
    @Column(nullable = false) private Long equipmentId;
    @Column(nullable = false) private int rank;
    @Column(precision = 5, scale = 2) private BigDecimal score;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MatchVerdict verdict;
    @Column(columnDefinition = "text") private String reason;
    @Column(nullable = false) private boolean usedMockData;
    @OneToMany(mappedBy = "result", cascade = CascadeType.ALL) @OrderBy("id ASC")
    private List<MatchConditionCheck> checks = new ArrayList<>();
    public MatchResult(MatchRun run, Long equipmentId, int rank, BigDecimal score, MatchVerdict verdict,
            boolean mock, String reason, List<MatchResponse.Check> checks) {
        this.run = run; this.equipmentId = equipmentId; this.rank = rank; this.score = score;
        this.verdict = verdict; this.usedMockData = mock; this.reason = reason;
        checks.forEach(c -> this.checks.add(new MatchConditionCheck(this, c)));
    }
}
