package com.myrobo.ops;

import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 백업할 데이터베이스. 모든 테이블을 한 시점의 일관된 모습으로 읽도록 하나의 스냅샷 안에서만 읽게 한다
 * (테이블마다 따로 읽으면 그 사이에 입력된 기록 때문에 서로 어긋날 수 있다).
 * 실제 구현은 PostgreSQL을 쓰고, 테스트에서는 가짜로 바꿔 끼운다.
 */
public interface BackupSource {

    <T> T snapshot(Function<Snapshot, T> work);

    interface Snapshot {

        /** 백업 대상 테이블과, 각 테이블이 외래키로 참조하는 테이블. 새 테이블이 생겨도 자동으로 따라온다. */
        Map<String, Set<String>> tables();

        long count(String table);

        /** 테이블을 헤더가 있는 CSV로 내보내고 내보낸 행 수를 돌려준다. */
        long copyOut(String table, OutputStream out);

        /** 마이그레이션 버전. 복구할 때 같은 구조인지 확인하는 데 쓴다. */
        String schemaVersion();
    }
}
