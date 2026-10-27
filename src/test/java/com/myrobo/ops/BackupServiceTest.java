package com.myrobo.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 백업 zip 만들기: 내용, 검증, 실패 시 정리, 보관 개수. */
class BackupServiceTest {

    /** 2026-11-01 09:00 KST (일요일). */
    private static final Clock SUNDAY = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    static FakeDb populated() {
        return FakeDb.sample()
                .add("goal", "1,OPEN")
                .add("account", "1,SAMSUNG")
                .add("instrument", "VTI,etf", "BIL,etf")
                .add("order_plan", "7,SENT");
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesOneCsvPerTableAndAManifestInParentFirstOrder(@TempDir Path dir) throws IOException {
        var result = new BackupService(populated(), dir, SUNDAY, 8).run();

        assertEquals(dir.resolve("myrobo-2026-11-01.zip"), result.file());
        assertEquals(5, result.tables());
        assertEquals(5, result.rows()); // 1 + 1 + 2 + 1 + 0
        assertEquals(List.of("myrobo-2026-11-01.zip"), names(dir)); // .part가 남지 않는다
        try (ZipFile zip = new ZipFile(result.file().toFile())) {
            BackupManifest m = BackupArchive.verify(zip);
            assertEquals("3", m.schema());
            assertTrue(m.created().startsWith("2026-11-01T09:00"), m.created());
            assertEquals(List.of("goal", "account", "instrument", "order_plan", "order_plan_item"),
                    m.tables().stream().map(BackupManifest.Entry::table).toList());
            String csv = new String(zip.getInputStream(zip.getEntry("instrument.csv")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals("id,v\nVTI,etf\nBIL,etf\n", csv);
        }
    }

    @Test
    void aRowCountMismatchFailsTheBackupAndLeavesNothingBehind(@TempDir Path dir) throws IOException {
        var db = populated();
        db.copyOutMiscount = 1;

        var e = assertThrows(IllegalStateException.class, () -> new BackupService(db, dir, SUNDAY, 8).run());

        assertTrue(e.getMessage().contains("다름"), e.getMessage());
        assertEquals(List.of(), names(dir));
    }

    @Test
    void aDatabaseWithNoHoldingsAtAllIsNotBackedUp(@TempDir Path dir) throws IOException {
        // 복구하려고 새 DB로 앱을 켠 직후: 기준 데이터만 있고 잔고가 없다. 같은 날 좋은 백업이 이미 있다
        new BackupService(populated().table("holding_snapshot").table("cash_balance").add("cash_balance", "1,KRW"),
                dir, SUNDAY, 8).run();
        var empty = FakeDb.sample().add("goal", "1,OPEN").table("holding_snapshot").table("cash_balance");

        var e = assertThrows(IllegalStateException.class, () -> new BackupService(empty, dir, SUNDAY, 1).run());

        assertTrue(e.getMessage().contains("잔고 기록이 하나도 없는"), e.getMessage());
        try (ZipFile zip = new ZipFile(dir.resolve("myrobo-2026-11-01.zip").toFile())) {
            assertEquals(1, BackupArchive.verify(zip).tables().stream()
                    .filter(t -> t.table().equals("cash_balance")).findFirst().orElseThrow().rows()); // 덮어쓰지 않음
        }
    }

    @Test
    void oneHoldingsRowIsEnoughToBackUp(@TempDir Path dir) {
        var db = FakeDb.sample().table("holding_snapshot").table("cash_balance").add("holding_snapshot", "1,VTI,10");

        assertEquals(1, new BackupService(db, dir, SUNDAY, 8).run().rows());
    }

    @Test
    void aFailedRunKeepsTheEarlierBackups(@TempDir Path dir) throws IOException {
        new BackupService(populated(), dir, Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC), 8).run();
        var bad = populated();
        bad.copyOutMiscount = 1;

        assertThrows(IllegalStateException.class, () -> new BackupService(bad, dir, SUNDAY, 1).run());

        assertEquals(List.of("myrobo-2026-10-25.zip"), names(dir)); // 실패한 실행은 지우지도 않는다
    }

    @Test
    void keepsOnlyTheNewestAfterASuccessAndLeavesOtherFilesAlone(@TempDir Path dir) throws IOException {
        for (String old : List.of("myrobo-2026-10-04.zip", "myrobo-2026-10-11.zip", "myrobo-2026-10-18.zip")) {
            Files.writeString(dir.resolve(old), "x");
        }
        Files.writeString(dir.resolve("notes.txt"), "내 메모");

        new BackupService(populated(), dir, SUNDAY, 2).run();

        assertEquals(List.of("myrobo-2026-10-18.zip", "myrobo-2026-11-01.zip", "notes.txt"), names(dir));
    }

    @Test
    void runningTwiceOnTheSameDayReplacesThatDaysFile(@TempDir Path dir) throws IOException {
        var db = populated();
        new BackupService(db, dir, SUNDAY, 8).run();
        db.add("instrument", "GLDM,etf");

        var second = new BackupService(db, dir, SUNDAY, 8).run();

        assertEquals(List.of("myrobo-2026-11-01.zip"), names(dir));
        assertEquals(6, second.rows());
    }

    @Test
    void verifyCatchesAChangedFile(@TempDir Path dir) throws IOException {
        var result = new BackupService(populated(), dir, SUNDAY, 8).run();
        Path tampered = dir.resolve("tampered.zip");
        copyWithChange(result.file(), tampered, "goal.csv", "id,v\n1,CHANGED\n");

        try (ZipFile zip = new ZipFile(tampered.toFile())) {
            var e = assertThrows(IllegalStateException.class, () -> BackupArchive.verify(zip));
            assertTrue(e.getMessage().contains("goal.csv"), e.getMessage());
        }
    }

    @Test
    void verifyCatchesAMissingFileAndAMissingManifest(@TempDir Path dir) throws IOException {
        Path noCsv = dir.resolve("no-csv.zip");
        Path noManifest = dir.resolve("no-manifest.zip");
        var result = new BackupService(populated(), dir.resolve("src"), SUNDAY, 8).run();
        copyWithChange(result.file(), noCsv, "goal.csv", null);
        copyWithChange(result.file(), noManifest, BackupManifest.FILE, null);

        try (ZipFile a = new ZipFile(noCsv.toFile()); ZipFile b = new ZipFile(noManifest.toFile())) {
            assertThrows(IllegalStateException.class, () -> BackupArchive.verify(a));
            assertThrows(IllegalStateException.class, () -> BackupArchive.verify(b));
        }
    }

    @Test
    void theBackupIsTakenInsideOneSnapshot(@TempDir Path dir) {
        List<String> events = new ArrayList<>();
        var db = populated();
        BackupSource spy = new BackupSource() {
            @Override public <T> T snapshot(java.util.function.Function<Snapshot, T> work) {
                events.add("open");
                T r = db.snapshot(work);
                events.add("close");
                return r;
            }
        };

        new BackupService(spy, dir, SUNDAY, 8).run();

        assertEquals(List.of("open", "close"), events); // 테이블마다 따로 열지 않는다
    }

    /** zip을 복사하면서 한 항목을 바꾸거나(내용 지정) 뺀다(null). */
    static void copyWithChange(Path from, Path to, String entryName, String newContent) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(from.toFile())) {
            for (var en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                entries.put(e.getName(), zip.getInputStream(e).readAllBytes());
            }
        }
        if (newContent == null) {
            entries.remove(entryName);
        } else {
            entries.put(entryName, newContent.getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(to.getParent());
        try (OutputStream o = Files.newOutputStream(to); ZipOutputStream z = new ZipOutputStream(o)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        assertFalse(entries.containsKey("nope"));
    }
}
