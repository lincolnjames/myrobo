package com.myrobo.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 설정 파일이 통과하는지, 그리고 위험한 실수를 하면 시작이 막히는지 확인한다. */
class RoboConfigFilesTest {

    private static final Path REAL = Path.of("config/robo-config.yml");

    @TempDir
    Path tmp;

    @Test
    void realConfigIsValid() throws IOException {
        LoadedConfig loaded = RoboConfigFiles.load(REAL);

        assertEquals("SAMSUNG", loaded.config().coreBroker());
        assertEquals(64, loaded.sha256().length());
        // 기한 없는 프로필: 위성 10%는 SATELLITE 키로 남고 나머지는 종목 기호로 바뀐다
        var targets = loaded.config().targetsFor("OPEN");
        assertEquals(new BigDecimal("0.35"), targets.get("VTI").stripTrailingZeros());
        assertEquals(new BigDecimal("0.1"), targets.get("SATELLITE").stripTrailingZeros());
    }

    @Test
    void profileNotSummingTo100IsRejected() throws IOException {
        ConfigException e = assertThrows(ConfigException.class, () -> load(s ->
                s.replace("12M:  { US_STOCK: 20, MID_TREASURY: 20,", "12M:  { US_STOCK: 20, MID_TREASURY: 25,")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("profiles.12M") && p.contains("100")), e.getMessage());
    }

    @Test
    void equityAboveCapIsRejected() throws IOException {
        ConfigException e = assertThrows(ConfigException.class, () -> load(s ->
                s.replace("36M:  { US_STOCK: 35, MID_TREASURY: 30,", "36M:  { US_STOCK: 50, MID_TREASURY: 15,")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("profiles.36M") && p.contains("equityCapPct")),
                e.getMessage());
    }

    @Test
    void untradableSymbolIsRejected() throws IOException {
        ConfigException e = assertThrows(ConfigException.class, () -> load(s ->
                s.replace("MID_TREASURY: { symbol: SCHR,", "MID_TREASURY: { symbol: VGIT,")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("VGIT") && p.contains("SAMSUNG")), e.getMessage());
    }

    @Test
    void hashIgnoresWindowsLineEndings() throws IOException {
        byte[] unix = Files.readAllBytes(REAL);
        byte[] windows = new String(unix, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(ConfigHash.sha256(unix), ConfigHash.sha256(windows));
    }

    private LoadedConfig load(UnaryOperator<String> edit) throws IOException {
        String original = Files.readString(REAL);
        String changed = edit.apply(original);
        if (changed.equals(original)) {
            throw new IllegalStateException("테스트의 치환 대상 문자열이 설정 파일에 없음");
        }
        Path p = tmp.resolve("robo-config.yml");
        Files.writeString(p, changed);
        return RoboConfigFiles.load(p);
    }
}
