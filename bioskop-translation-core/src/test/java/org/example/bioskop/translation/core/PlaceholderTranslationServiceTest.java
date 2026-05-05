package org.example.bioskop.translation.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceholderTranslationServiceTest {
    private final PlaceholderTranslationService service = new PlaceholderTranslationService();

    @Test
    void returnsPendingTranslationResponse() {
        UUID sourceId = UUID.randomUUID();
        TranslationResponse response = service.requestTranslation(new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            "/source/exercise-ru.srt",
            "en",
            "ru"
        ));

        assertEquals(sourceId, response.sourceTextId());
        assertEquals("/source/exercise-en.srt", response.sourcePath());
        assertEquals("/source/exercise-ru.srt", response.targetPath());
        assertEquals("en", response.sourceLang());
        assertEquals("ru", response.targetLang());
        assertEquals(TranslationStatus.PENDING, response.status());
    }

    @Test
    void rejectsBlankTargetLanguage() {
        UUID sourceId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> service.requestTranslation(new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            null,
            "en",
            " "
        )));
    }
}
