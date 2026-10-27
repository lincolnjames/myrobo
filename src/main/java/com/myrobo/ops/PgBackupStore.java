package com.myrobo.ops;

import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * PostgreSQL용 백업·복구. COPY로 테이블을 CSV로 주고받아 열 타입과 NULL이 그대로 보존된다.
 * pg_dump·psql 같은 외부 프로그램이 필요 없다. 대상 스키마는 연결의 현재 스키마(robo)다.
 */
public final class PgBackupStore implements BackupSource, RestoreTarget {

    private static final String TABLES = "select table_name from information_schema.tables"
            + " where table_schema = current_schema() and table_type = 'BASE TABLE'"
            + " and table_name <> 'flyway_schema_history'";
    private static final String REFS = "select child.relname, parent.relname from pg_constraint k"
            + " join pg_class child on child.oid = k.conrelid join pg_class parent on parent.oid = k.confrelid"
            + " where k.contype = 'f' and child.relnamespace = (select oid from pg_namespace where nspname = current_schema())";
    private static final String VERSION = "select version from flyway_schema_history"
            + " where success and version is not null order by installed_rank desc limit 1";
    private static final String HAS_IDENTITY_ID = "select 1 from information_schema.columns"
            + " where table_schema = current_schema() and table_name = ? and column_name = 'id' and is_identity = 'YES'";

    private final DataSource dataSource;

    public PgBackupStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T snapshot(Function<BackupSource.Snapshot, T> work) {
        return inTransaction(true, conn -> work.apply(new Reader(conn)));
    }

    @Override
    public <T> T transaction(Function<RestoreTarget.Tx, T> work) {
        return inTransaction(false, conn -> work.apply(new Writer(conn)));
    }

    private <T> T inTransaction(boolean readOnlySnapshot, Function<Connection, T> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            if (readOnlySnapshot) {
                // 모든 테이블을 같은 시점으로 읽는다
                conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                conn.setReadOnly(true);
            }
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("데이터베이스 작업 실패 (" + e.getSQLState() + ")", e);
        }
    }

    /** 식별자를 따옴표로 감싼다. 이름은 카탈로그에서 확인한 것만 쓰지만 이중으로 막는다. */
    static String q(String ident) {
        return '"' + ident.replace("\"", "\"\"") + '"';
    }

    private static Set<String> tableNames(Connection conn) throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(TABLES); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static String schemaVersion(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(VERSION); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "unknown";
        }
    }

    private static final class Reader implements BackupSource.Snapshot {

        private final Connection conn;

        Reader(Connection conn) {
            this.conn = conn;
        }

        @Override
        public Map<String, Set<String>> tables() {
            try {
                Map<String, Set<String>> refs = new HashMap<>();
                for (String t : tableNames(conn)) {
                    refs.put(t, new HashSet<>());
                }
                try (PreparedStatement ps = conn.prepareStatement(REFS); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Set<String> parents = refs.get(rs.getString(1));
                        if (parents != null) {
                            parents.add(rs.getString(2));
                        }
                    }
                }
                return refs;
            } catch (SQLException e) {
                throw new IllegalStateException("테이블 목록을 읽지 못함 (" + e.getSQLState() + ")", e);
            }
        }

        @Override
        public long count(String table) {
            try (PreparedStatement ps = conn.prepareStatement("select count(*) from " + q(table));
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            } catch (SQLException e) {
                throw new IllegalStateException(table + " 행 수를 세지 못함 (" + e.getSQLState() + ")", e);
            }
        }

        @Override
        public long copyOut(String table, OutputStream out) {
            try {
                return conn.unwrap(PGConnection.class).getCopyAPI()
                        .copyOut("copy " + q(table) + " to stdout with (format csv, header true)", out);
            } catch (SQLException e) {
                throw new IllegalStateException(table + " 내보내기 실패 (" + e.getSQLState() + ")", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String schemaVersion() {
            try {
                return PgBackupStore.schemaVersion(conn);
            } catch (SQLException e) {
                throw new IllegalStateException("스키마 버전을 읽지 못함 (" + e.getSQLState() + ")", e);
            }
        }
    }

    private static final class Writer implements RestoreTarget.Tx {

        private final Connection conn;

        Writer(Connection conn) {
            this.conn = conn;
        }

        @Override
        public String schemaVersion() {
            try {
                return PgBackupStore.schemaVersion(conn);
            } catch (SQLException e) {
                throw new IllegalStateException("스키마 버전을 읽지 못함 (" + e.getSQLState() + ")", e);
            }
        }

        @Override
        public Set<String> tables() {
            try {
                return tableNames(conn);
            } catch (SQLException e) {
                throw new IllegalStateException("테이블 목록을 읽지 못함 (" + e.getSQLState() + ")", e);
            }
        }

        @Override
        public boolean isEmpty(String table) {
            try (PreparedStatement ps = conn.prepareStatement("select not exists (select 1 from " + q(table) + ")");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            } catch (SQLException e) {
                throw new IllegalStateException(table + " 상태를 확인하지 못함 (" + e.getSQLState() + ")", e);
            }
        }

        @Override
        public void deleteAll(String table) {
            try (PreparedStatement ps = conn.prepareStatement("delete from " + q(table))) {
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(table + " 비우기 실패 (" + e.getSQLState() + ")", e);
            }
        }

        @Override
        public long copyIn(String table, InputStream csv) {
            try {
                return conn.unwrap(PGConnection.class).getCopyAPI()
                        .copyIn("copy " + q(table) + " from stdin with (format csv, header true)", csv);
            } catch (SQLException e) {
                throw new IllegalStateException(table + " 복구 실패 (" + e.getSQLState() + ")", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void resetSequence(String table) {
            try {
                boolean identity;
                try (PreparedStatement ps = conn.prepareStatement(HAS_IDENTITY_ID)) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        identity = rs.next();
                    }
                }
                if (!identity) {
                    return;
                }
                String sql = "select setval(pg_get_serial_sequence(quote_ident(current_schema()) || '.' || quote_ident(?), 'id'),"
                        + " coalesce(max(id), 1), max(id) is not null) from " + q(table);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, table);
                    ps.executeQuery().close();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(table + " 번호를 이어 붙이지 못함 (" + e.getSQLState() + ")", e);
            }
        }
    }
}
