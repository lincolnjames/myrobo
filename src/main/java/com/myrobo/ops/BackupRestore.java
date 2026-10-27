package com.myrobo.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * 백업 zip을 빈 데이터베이스에 되돌린다. 재해 복구용이라 일부러 보수적이다.
 *
 * <ul>
 *   <li>zip의 해시를 먼저 검증한다. 데이터베이스는 그 뒤에야 건드린다</li>
 *   <li>마이그레이션 버전과 테이블 구성이 같아야 한다</li>
 *   <li>직접 입력한 기록이 하나라도 있으면 거부한다. 앱이 시작할 때 설정에서 채우는 기준 데이터
 *       (목표·계좌·종목)만 있는 상태는 "비었다"로 보고 백업 내용으로 바꾼다</li>
 *   <li>전부 한 트랜잭션이라 실패하면 아무것도 바뀌지 않는다</li>
 * </ul>
 */
public final class BackupRestore {

    /** 앱이 시작할 때 설정에서 채우는 테이블 (ReferenceDataBootstrap). */
    static final Set<String> BOOTSTRAPPED = Set.of("goal", "account", "instrument");

    public record Result(Map<String, Long> rows) {
    }

    private final RestoreTarget target;
    private final Path dir;

    public BackupRestore(RestoreTarget target, Path dir) {
        this.target = target;
        this.dir = dir;
    }

    /** @param fileName 백업 폴더 안의 myrobo-YYYY-MM-DD.zip 이름. 경로는 받지 않는다 */
    public Result run(String fileName) {
        if (fileName == null || !fileName.matches(BackupService.FILE_PATTERN)) {
            throw new IllegalArgumentException("백업 파일 이름은 myrobo-YYYY-MM-DD.zip 형식이어야 함");
        }
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("백업 파일이 없음: " + fileName);
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            BackupManifest manifest = BackupArchive.verify(zip);
            return target.transaction(tx -> restore(tx, zip, manifest));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Result restore(RestoreTarget.Tx tx, ZipFile zip, BackupManifest manifest) {
        if (!manifest.schema().equals(tx.schemaVersion())) {
            throw new IllegalStateException("스키마 버전이 다름: 백업 " + manifest.schema() + ", DB " + tx.schemaVersion());
        }
        List<String> order = manifest.tables().stream().map(BackupManifest.Entry::table).toList();
        if (!Set.copyOf(order).equals(tx.tables())) {
            throw new IllegalStateException("테이블 구성이 백업과 다름: 백업 " + order + ", DB " + tx.tables());
        }
        List<String> notEmpty = new ArrayList<>();
        for (String t : order) {
            if (!BOOTSTRAPPED.contains(t) && !tx.isEmpty(t)) {
                notEmpty.add(t);
            }
        }
        if (!notEmpty.isEmpty()) {
            throw new IllegalStateException("비어 있지 않은 테이블이 있어 복구하지 않음 (빈 데이터베이스에만 복구함): " + notEmpty);
        }
        for (int i = order.size() - 1; i >= 0; i--) { // 자식 → 부모 순으로 비운다
            if (BOOTSTRAPPED.contains(order.get(i))) {
                tx.deleteAll(order.get(i));
            }
        }
        Map<String, Long> restored = new LinkedHashMap<>();
        for (BackupManifest.Entry e : manifest.tables()) {
            try (InputStream in = zip.getInputStream(zip.getEntry(e.table() + ".csv"))) {
                long n = tx.copyIn(e.table(), in);
                if (n != e.rows()) {
                    throw new IllegalStateException(e.table() + ": 넣은 행 " + n + "개가 백업의 " + e.rows() + "개와 다름");
                }
                restored.put(e.table(), n);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        for (String t : order) {
            tx.resetSequence(t);
        }
        return new Result(restored);
    }
}
