package org.example.bioskop.translation.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.example.bioskop.translation.core.TranslationFormat;
import org.example.bioskop.translation.core.context.TranslationContext;
import org.example.bioskop.translation.core.srt.SubtitleCue;
import org.junit.jupiter.api.Test;

class AiTranslationClientTest {
    @Test
    void fakeClientTranslatesEachCue() {
        FakeAiTranslationClient client = new FakeAiTranslationClient(cue -> "[" + cue.text() + "]");

        List<CueTranslation> translations = client.translateBatch(new TranslationBatchRequest(
            "en",
            "ru",
            TranslationFormat.QUICK,
            List.of(new SubtitleCue(1, 0, 1000, "Hello", null)),
            TranslationContext.empty()
        ));

        assertEquals(List.of(new CueTranslation(1, "[Hello]")), translations);
    }

    @Test
    void validatorRejectsMissingCueTranslation() {
        assertThrows(IllegalArgumentException.class, () -> TranslationResponseValidator.validateCoverage(
            List.of(
                new SubtitleCue(1, 0, 1000, "One", null),
                new SubtitleCue(2, 1000, 2000, "Two", null)
            ),
            List.of(new CueTranslation(1, "Один"))
        ));
    }
}
