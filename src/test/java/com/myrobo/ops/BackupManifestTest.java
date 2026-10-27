package com.myrobo.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupManifestTest {

    @Test
    void renderAndParseRoundTrip() {
        var m = new BackupManifest("2026-11-01T09:00:00+09:00", "3",
                List.of(new BackupManifest.Entry("goal", 1, "aa"), new BackupManifest.Entry("account", 2, "bb")));

        assertEquals(m, BackupManifest.parse(m.render()));
    }

    @Test
    void parseRejectsUnknownFormatsAndLines() {
        assertThrows(IllegalStateException.class, () -> BackupManifest.parse("something else\n"));
        assertThrows(IllegalStateException.class,
                () -> BackupManifest.parse("myrobo-backup 1\ncreated\tx\nschema\t3\nweird\ty\n"));
        assertThrows(IllegalStateException.class, () -> BackupManifest.parse("myrobo-backup 1\ncreated\tx\n"));
    }
}
