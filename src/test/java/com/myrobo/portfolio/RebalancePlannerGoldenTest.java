package com.myrobo.portfolio;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 골든 케이스 15개가 모두 정답과 완전히 일치해야 한다 (주문 줄, 환전, 비중, 현금까지).
 * 정답을 이 테스트나 JSON에서 손으로 고치지 말 것. tools/rebalance_ref.py를 고치고 gen_cases.py로 다시 만든다.
 */
class RebalancePlannerGoldenTest {

    private static GoldenCases golden;

    @BeforeAll
    static void load() throws IOException {
        golden = GoldenCases.load(RebalancePlannerGoldenTest.class.getResourceAsStream("/golden_cases.json"));
    }

    static Stream<Arguments> cases() throws IOException {
        load();
        return golden.cases().stream().map(c -> Arguments.of(c.get("id"), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @SuppressWarnings("unchecked")
    void matchesGolden(String id, Map<String, Object> c) {
        RebalancePlanner planner = new RebalancePlanner(golden.config());
        Plan actual = planner.plan(GoldenCases.toInput((Map<String, Object>) c.get("input")));

        List<String> diffs = GoldenCases.diff(actual, (Map<String, Object>) c.get("expected"));

        assertEquals(List.of(), diffs, id + " — " + c.get("description"));
    }
}
