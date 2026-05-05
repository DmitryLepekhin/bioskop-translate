package org.example.bioskop.translation.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileTranslationStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void writesReadsAndChecksText() {
        LocalFileTranslationStorage storage = new LocalFileTranslationStorage(tempDir);

        assertFalse(storage.exists("/bioskop-translate/source/input.srt"));

        storage.writeText("/bioskop-translate/source/input.srt", "hello\nworld");

        assertTrue(storage.exists("/bioskop-translate/source/input.srt"));
        assertEquals("hello\nworld", storage.readText("/bioskop-translate/source/input.srt"));
    }

    @Test
    void rejectsPathTraversal() {
        LocalFileTranslationStorage storage = new LocalFileTranslationStorage(tempDir);

        assertThrows(IllegalArgumentException.class, () -> storage.writeText("../outside.txt", "bad"));
    }
}
