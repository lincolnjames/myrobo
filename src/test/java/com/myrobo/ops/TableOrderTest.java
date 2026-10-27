package com.myrobo.ops;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 외래키 순서 정렬: 복구 때 부모가 먼저 들어가야 한다. */
class TableOrderTest {

    @Test
    void parentsComeBeforeChildrenAndTiesAreAlphabetical() {
        Map<String, Set<String>> refs = new LinkedHashMap<>();
        refs.put("order_plan_item", Set.of("order_plan"));
        refs.put("order_plan", Set.of("account"));
        refs.put("account", Set.of("goal"));
        refs.put("goal", Set.of());
        refs.put("instrument", Set.of());
        refs.put("execution", Set.of("order_plan_item"));

        assertEquals(List.of("goal", "account", "instrument", "order_plan", "order_plan_item", "execution"),
                TableOrder.parentsFirst(refs));
    }

    @Test
    void selfReferencesAndUnknownParentsAreIgnored() {
        Map<String, Set<String>> refs = Map.of("a", Set.of("a"), "b", Set.of("not_a_backed_up_table"));

        assertEquals(List.of("a", "b"), TableOrder.parentsFirst(refs));
    }

    @Test
    void aCycleIsAnError() {
        Map<String, Set<String>> refs = Map.of("a", Set.of("b"), "b", Set.of("a"));

        assertThrows(IllegalStateException.class, () -> TableOrder.parentsFirst(refs));
    }

    @Test
    void emptyInputGivesEmptyOrder() {
        assertEquals(List.of(), TableOrder.parentsFirst(Map.of()));
    }
}
