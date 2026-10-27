package com.myrobo.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 백업 zip 읽기와 검증. 만든 직후의 자체 검증과, 복구 직전의 검증에 같은 코드를 쓴다. */
final class BackupArchive {

    private BackupArchive() {
    }

    /**
     * manifest를 읽고, 적힌 모든 CSV가 있는지와 내용 해시가 맞는지 확인한다.
     *
     * @throws IllegalStateException 빠졌거나 내용이 바뀐 파일이 있을 때
     */
    static BackupManifest verify(ZipFile zip) throws IOException {
        ZipEntry m = zip.getEntry(BackupManifest.FILE);
        if (m == null) {
            throw new IllegalStateException("백업에 " + BackupManifest.FILE + "이 없음");
        }
        BackupManifest manifest;
        try (InputStream in = zip.getInputStream(m)) {
            manifest = BackupManifest.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (BackupManifest.Entry e : manifest.tables()) {
            ZipEntry entry = zip.getEntry(e.table() + ".csv");
            if (entry == null) {
                throw new IllegalStateException("백업에 " + e.table() + ".csv가 없음");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String actual = sha256Hex(in);
                if (!actual.equals(e.sha256())) {
                    throw new IllegalStateException(e.table() + ".csv의 내용이 manifest와 다름 (손상되었거나 바뀜)");
                }
            }
        }
        return manifest;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    private static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return hex(md.digest());
    }
}
