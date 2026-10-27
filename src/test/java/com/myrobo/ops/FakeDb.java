package com.myrobo.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 백업·복구 테스트용 메모리 데이터베이스. 테이블마다 "id,v" 두 열의 CSV 행만 가진다. */
final class FakeDb implements BackupSource, RestoreTarget {

    final Map<String, Set<String>> refs = new LinkedHashMap<>();
    final Map<String, List<String>> rows = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    String schema = "3";
    /** 내보낼 때 실제보다 행을 더/덜 센 것처럼 속이는 값. */
    long copyOutMiscount;
    /** 복구할 때 넣은 행 수를 속이는 값. */
    long copyInMiscount;

    /** 외래키 구조: order_plan_item → order_plan → account → goal. instrument는 독립. */
    static FakeDb sample() {
        FakeDb db = new FakeDb();
        db.table("goal");
        db.table("account", "goal");
        db.table("instrument");
        db.table("order_plan", "account");
        db.table("order_plan_item", "order_plan");
        return db;
    }

    FakeDb table(String name, String... parents) {
        refs.put(name, new LinkedHashSet<>(List.of(parents)));
        rows.put(name, new ArrayList<>());
        return this;
    }

    FakeDb add(String table, String... dataRows) {
        rows.get(table).addAll(List.of(dataRows));
        return this;
    }

    @Override
    public <T> T snapshot(Function<Snapshot, T> work) {
        return work.apply(new Snapshot() {
            @Override public Map<String, Set<String>> tables() { return refs; }
            @Override public long count(String table) { return rows.get(table).size(); }
            @Override public long copyOut(String table, OutputStream out) {
                try {
                    StringBuilder sb = new StringBuilder("id,v\n");
                    rows.get(table).forEach(r -> sb.append(r).append('\n'));
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    return rows.get(table).size() + copyOutMiscount;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            @Override public String schemaVersion() { return schema; }
        });
    }

    @Override
    public <T> T transaction(Function<Tx, T> work) {
        Map<String, List<String>> before = new LinkedHashMap<>();
        rows.forEach((k, v) -> before.put(k, new ArrayList<>(v)));
        List<String> logBefore = new ArrayList<>(log);
        try {
            return work.apply(new Tx() {
                @Override public String schemaVersion() { return schema; }
                @Override public Set<String> tables() { return rows.keySet(); }
                @Override public boolean isEmpty(String table) { return rows.get(table).isEmpty(); }
                @Override public void deleteAll(String table) { log.add("delete " + table); rows.get(table).clear(); }
                @Override public long copyIn(String table, InputStream csv) {
                    try {
                        String[] lines = new String(csv.readAllBytes(), StandardCharsets.UTF_8).split("\n");
                        for (int i = 1; i < lines.length; i++) {
                            rows.get(table).add(lines[i]);
                        }
                        log.add("copyIn " + table);
                        return lines.length - 1 + copyInMiscount;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                @Override public void resetSequence(String table) { log.add("reset " + table); }
            });
        } catch (RuntimeException e) {
            before.forEach((k, v) -> rows.put(k, v)); // 롤백
            log.clear();
            log.addAll(logBefore);
            throw e;
        }
    }
}
