package kr.astra.requirement.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import kr.astra.global.entity.BaseTimeEntity;
import kr.astra.requirement.dto.RequirementInput;
import lombok.*;

@Getter
@Entity
@Table(name = "test_requirements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestRequirement extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long userId;
    private Long chatSessionId;
    @Column(length = 50) private String testTypeCode;
    @Column(length = 200) private String title;
    @Column(columnDefinition = "text") private String purpose;
    @Column(length = 200) private String sampleName;
    private Integer sampleQuantity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RequirementStatus status = RequirementStatus.DRAFT;
    @OneToMany(mappedBy = "requirement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<RequirementCondition> conditions = new ArrayList<>();

    public TestRequirement(Long userId, Long chatSessionId, RequirementInput input) {
        this.userId = userId;
        this.chatSessionId = chatSessionId;
        this.testTypeCode = input.testTypeCode();
        update(input.title(), input.purpose(), input.sampleName(), input.sampleQuantity());
    }

    public void update(String title, String purpose, String sampleName, Integer sampleQuantity) {
        this.title = title;
        this.purpose = purpose;
        this.sampleName = sampleName;
        this.sampleQuantity = sampleQuantity;
    }

    public void confirm() { status = RequirementStatus.CONFIRMED; }
}
