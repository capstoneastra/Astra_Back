package kr.astra.matching.entity;
import jakarta.persistence.*;
import kr.astra.matching.dto.MatchResponse;
import lombok.*;
@Getter @Entity @Table(name = "match_condition_checks") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchConditionCheck {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "match_result_id", nullable = false)
    private MatchResult result;
    @Column(nullable = false, length = 80) private String conditionKey;
    @Column(length = 200) private String requiredText;
    @Column(length = 200) private String equipmentText;
    @Enumerated(EnumType.STRING) @Column(name = "result", nullable = false, length = 20) private CheckResult verdict;
    @Column(length = 300) private String note;
    public MatchConditionCheck(MatchResult result, MatchResponse.Check c) {
        this.result = result; conditionKey = c.conditionKey(); requiredText = c.requiredText();
        equipmentText = c.equipmentText(); verdict = c.result(); note = c.note();
    }
    public MatchResponse.Check response() { return new MatchResponse.Check(conditionKey, requiredText, equipmentText, verdict, note); }
}
