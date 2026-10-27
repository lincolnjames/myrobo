package com.myrobo.ops;

import java.io.InputStream;
import java.util.Set;
import java.util.function.Function;

/** 복구할 데이터베이스. 모든 작업이 하나의 트랜잭션이라, 중간에 실패하면 아무것도 바뀌지 않는다. */
public interface RestoreTarget {

    <T> T transaction(Function<Tx, T> work);

    interface Tx {

        String schemaVersion();

        Set<String> tables();

        boolean isEmpty(String table);

        void deleteAll(String table);

        /** 헤더가 있는 CSV를 넣고 넣은 행 수를 돌려준다. */
        long copyIn(String table, InputStream csv);

        /** id 자동 번호를 현재 최대값 다음부터 이어지게 맞춘다 (id 열이 없는 테이블이면 아무것도 안 한다). */
        void resetSequence(String table);
    }
}
