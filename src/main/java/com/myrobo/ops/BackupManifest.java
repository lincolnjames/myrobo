package com.myrobo.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * 백업 zip 안의 manifest.txt. 테이블 순서(복구 때 이 순서로 넣는다), 행 수, 내용 해시를 담는다.
 * 탭으로 나눈 단순한 줄 형식이라 사람이 열어 봐도 읽힌다.
 */
record BackupManifest(String created, String schema, List<Entry> tables) {

    static final String FILE = "manifest.txt";
    private static final String HEADER = "myrobo-backup 1";

    record Entry(String table, long rows, String sha256) {
    }

    String render() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("created\t").append(created).append('\n');
        sb.append("schema\t").append(schema).append('\n');
        for (Entry e : tables) {
            sb.append("table\t").append(e.table()).append('\t').append(e.rows()).append('\t').append(e.sha256())
                    .append('\n');
        }
        return sb.toString();
    }

    static BackupManifest parse(String text) {
        String[] lines = text.split("\n");
        if (lines.length == 0 || !HEADER.equals(lines[0].strip())) {
            throw new IllegalStateException("백업 manifest 형식을 알 수 없음");
        }
        String created = null;
        String schema = null;
        List<Entry> tables = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] f = lines[i].split("\t");
            switch (f[0]) {
                case "created" -> created = f[1];
                case "schema" -> schema = f[1];
                case "table" -> tables.add(new Entry(f[1], Long.parseLong(f[2]), f[3]));
                default -> throw new IllegalStateException("백업 manifest에 알 수 없는 줄: " + f[0]);
            }
        }
        if (created == null || schema == null) {
            throw new IllegalStateException("백업 manifest에 created 또는 schema가 없음");
        }
        return new BackupManifest(created, schema, List.copyOf(tables));
    }
}
