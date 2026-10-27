package com.myrobo.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 외래키 순서 정렬: 참조당하는 테이블(부모)이 먼저 오도록. 같은 단계에서는 이름순이라 결과가 늘 같다. */
final class TableOrder {

    private TableOrder() {
    }

    /**
     * @param refs 테이블 → 그 테이블이 참조하는 테이블들. 자기 참조와 목록에 없는 테이블은 무시한다
     * @throws IllegalStateException 서로를 참조해 순서를 정할 수 없을 때
     */
    static List<String> parentsFirst(Map<String, Set<String>> refs) {
        List<String> order = new ArrayList<>();
        Set<String> placed = new TreeSet<>();
        Set<String> left = new TreeSet<>(refs.keySet());
        while (!left.isEmpty()) {
            String next = null;
            for (String t : left) {
                boolean ready = true;
                for (String parent : refs.getOrDefault(t, Set.of())) {
                    if (!parent.equals(t) && refs.containsKey(parent) && !placed.contains(parent)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    next = t;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("테이블이 서로를 참조해 순서를 정할 수 없음: " + left);
            }
            order.add(next);
            placed.add(next);
            left.remove(next);
        }
        return order;
    }
}
