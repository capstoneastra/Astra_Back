package kr.astra.testtype.repository;

import java.util.List;
import kr.astra.testtype.entity.TestConditionRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestConditionRuleRepository extends JpaRepository<TestConditionRule, TestConditionRule.Key> {

    List<TestConditionRule> findAllByTestTypeCodeOrderByDisplayOrderAscConditionKeyAsc(String testTypeCode);
}
