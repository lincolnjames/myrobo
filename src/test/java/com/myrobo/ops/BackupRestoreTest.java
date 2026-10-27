package com.myrobo.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 복구: 백업 → 복구 왕복, 그리고 거부해야 할 때 아무것도 건드리지 않는지. */
class BackupRestoreTest {

    private static final Clock SUNDAY = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String FILE = "myrobo-2026-11-01.zip";

    private static void backup(FakeDb db, Path dir) {
        new BackupService(db, dir, SUNDAY, 8).run();
    }

    @Test
    void backupThenRestoreIntoAFreshDatabaseRoundTrips(@TempDir Path dir) {
        var original = BackupServiceTest.populated().add("order_plan_item", "1,BUY VTI");
        backup(original, dir);
        // 새 DB: 앱이 시작할 때 설정에서 채운 기준 데이터(옛 id와 다를 수 있음)만 있다
        var fresh = FakeDb.sample().add("goal", "9,STALE").add("account", "9,STALE").add("instrument", "VTI,stale");

        var result = new BackupRestore(fresh, dir).run(FILE);

        assertEquals(original.rows, fresh.rows);
        assertEquals(Map.of("goal", 1L, "account", 1L, "instrument", 2L, "order_plan", 1L, "order_plan_item", 1L),
                result.rows());
        assertEquals(List.of(
                "delete instrument", "delete account", "delete goal", // 기준 데이터는 자식 → 부모 순으로 비운다
                "copyIn goal", "copyIn account", "copyIn instrument", "copyIn order_plan", "copyIn order_plan_item",
                "reset goal", "reset account", "reset instrument", "reset order_plan", "reset order_plan_item"),
                fresh.log);
    }

    @Test
    void theAutoApprovalWrittenAtFirstBootIsReplacedNotRefused(@TempDir Path dir) {
        // 실제 복구 순서: 새 DB로 앱을 한 번 켜면 설정 최초 승인 한 줄이 rule_change에 생긴다
        var original = BackupServiceTest.populated().table("rule_change").add("rule_change", "1,APPROVED old-hash");
        backup(original, dir);
        var fresh = FakeDb.sample().table("rule_change").add("rule_change", "1,APPROVED auto");

        new BackupRestore(fresh, dir).run(FILE);

        assertEquals(List.of("1,APPROVED old-hash"), fresh.rows.get("rule_change"));
    }

    @Test
    void refusesWhenAnyHandEnteredTableHasData(@TempDir Path dir) {
        backup(BackupServiceTest.populated(), dir);
        var live = FakeDb.sample().add("order_plan", "5,SENT");

        var e = assertThrows(IllegalStateException.class, () -> new BackupRestore(live, dir).run(FILE));

        assertTrue(e.getMessage().contains("order_plan"), e.getMessage());
        assertEquals(List.of("5,SENT"), live.rows.get("order_plan"));
        assertEquals(List.of(), live.log);
    }

    @Test
    void refusesADifferentSchemaVersion(@TempDir Path dir) {
        backup(BackupServiceTest.populated(), dir);
        var other = FakeDb.sample();
        other.schema = "4";

        var e = assertThrows(IllegalStateException.class, () -> new BackupRestore(other, dir).run(FILE));

        assertTrue(e.getMessage().contains("스키마 버전"), e.getMessage());
        assertEquals(List.of(), other.log);
    }

    @Test
    void refusesADifferentTableSet(@TempDir Path dir) {
        backup(BackupServiceTest.populated(), dir);
        var other = FakeDb.sample().table("brand_new_table");

        var e = assertThrows(IllegalStateException.class, () -> new BackupRestore(other, dir).run(FILE));

        assertTrue(e.getMessage().contains("테이블 구성"), e.getMessage());
        assertEquals(List.of(), other.log);
    }

    @Test
    void aTamperedBackupIsRejectedBeforeTouchingTheDatabase(@TempDir Path dir) throws IOException {
        backup(BackupServiceTest.populated(), dir);
        Path good = dir.resolve(FILE);
        Path bad = dir.resolve("myrobo-2026-11-08.zip");
        BackupServiceTest.copyWithChange(good, bad, "instrument.csv", "id,v\nVTI,HACKED\n");
        var fresh = FakeDb.sample();

        assertThrows(IllegalStateException.class, () -> new BackupRestore(fresh, dir).run("myrobo-2026-11-08.zip"));

        assertEquals(List.of(), fresh.log);
        assertEquals(List.of(), fresh.rows.get("instrument"));
    }

    @Test
    void aRowCountMismatchRollsEverythingBack(@TempDir Path dir) {
        backup(BackupServiceTest.populated(), dir);
        var fresh = FakeDb.sample().add("goal", "9,STALE");
        fresh.copyInMiscount = 1;

        var e = assertThrows(IllegalStateException.class, () -> new BackupRestore(fresh, dir).run(FILE));

        assertTrue(e.getMessage().contains("다름"), e.getMessage());
        assertEquals(List.of("9,STALE"), fresh.rows.get("goal")); // 기준 데이터를 비운 것도 되돌아간다
        assertEquals(List.of(), fresh.rows.get("account"));
    }

    @Test
    void onlyPlainBackupFileNamesInsideTheFolderAreAccepted(@TempDir Path dir) {
        var fresh = FakeDb.sample();
        var restore = new BackupRestore(fresh, dir);

        assertThrows(IllegalArgumentException.class, () -> restore.run("../myrobo-2026-11-01.zip"));
        assertThrows(IllegalArgumentException.class, () -> restore.run("C:\\x\\myrobo-2026-11-01.zip"));
        assertThrows(IllegalArgumentException.class, () -> restore.run("backup.zip"));
        assertThrows(IllegalArgumentException.class, () -> restore.run(null));
        assertThrows(IllegalStateException.class, () -> restore.run("myrobo-2026-11-01.zip")); // 형식은 맞지만 없음
        assertEquals(List.of(), fresh.log);
    }
}
