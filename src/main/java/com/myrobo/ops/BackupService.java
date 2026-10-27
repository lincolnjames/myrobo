package com.myrobo.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 데이터베이스 전체를 테이블별 CSV로 묶은 zip 하나로 내보낸다 (myrobo-YYYY-MM-DD.zip, 한국 날짜).
 *
 * <p>한 스냅샷에서 읽고, 테이블마다 내보낸 행 수가 DB의 행 수와 같은지 보고, 다 쓴 뒤 zip을 다시 열어
 * 해시까지 맞는지 확인한 다음에야 제 이름으로 옮긴다. 실패하면 반쯤 쓴 파일을 남기지 않고, 성공한 뒤에만
 * 오래된 백업을 지운다.
 */
public final class BackupService {

    static final String FILE_PATTERN = "myrobo-\\d{4}-\\d{2}-\\d{2}\\.zip";
    private static final Pattern FILE = Pattern.compile(FILE_PATTERN);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    public record Result(Path file, long rows, int tables) {
    }

    private final BackupSource source;
    private final Path dir;
    private final Clock clock;
    private final int keep;

    public BackupService(BackupSource source, Path dir, Clock clock, int keep) {
        this.source = source;
        this.dir = dir;
        this.clock = clock;
        this.keep = Math.max(1, keep);
    }

    public Result run() {
        try {
            return create();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Result create() throws IOException {
        Files.createDirectories(dir);
        ZonedDateTime now = clock.instant().atZone(KST);
        Path target = dir.resolve("myrobo-" + now.toLocalDate() + ".zip");
        Path part = dir.resolve(target.getFileName() + ".part");
        BackupManifest manifest;
        try {
            manifest = source.snapshot(s -> {
                try {
                    return write(s, part, now);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            try (ZipFile zip = new ZipFile(part.toFile())) {
                BackupArchive.verify(zip);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        prune();
        long rows = manifest.tables().stream().mapToLong(BackupManifest.Entry::rows).sum();
        return new Result(target, rows, manifest.tables().size());
    }

    private BackupManifest write(BackupSource.Snapshot s, Path part, ZonedDateTime now) throws IOException {
        List<String> order = TableOrder.parentsFirst(s.tables());
        List<BackupManifest.Entry> entries = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part))) {
            for (String table : order) {
                zip.putNextEntry(new ZipEntry(table + ".csv"));
                MessageDigest md = BackupArchive.sha256();
                OutputStream sink = new DigestOutputStream(zip, md); // 닫지 않는다: zip 항목은 아래에서 닫는다
                long rows = s.copyOut(table, sink);
                long expected = s.count(table);
                if (rows != expected) {
                    throw new IllegalStateException(table + ": 내보낸 행 " + rows + "개가 DB의 " + expected + "개와 다름");
                }
                zip.closeEntry();
                entries.add(new BackupManifest.Entry(table, rows, BackupArchive.hex(md.digest())));
            }
            BackupManifest manifest = new BackupManifest(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    s.schemaVersion(), entries);
            zip.putNextEntry(new ZipEntry(BackupManifest.FILE));
            zip.write(manifest.render().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            return manifest;
        }
    }

    /** 최근 keep개만 남기고 지운다. 지우기에 실패해도 백업 자체는 성공으로 둔다. */
    private void prune() {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> old = files.filter(p -> FILE.matcher(p.getFileName().toString()).matches())
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .skip(keep).toList();
            for (Path p : old) {
                Files.deleteIfExists(p);
                log.info("오래된 백업 삭제: {}", p.getFileName());
            }
        } catch (IOException e) {
            log.warn("오래된 백업을 정리하지 못함 ({})", e.getClass().getSimpleName());
        }
    }
}
