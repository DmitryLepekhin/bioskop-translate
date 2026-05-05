package org.example.bioskop.translation.core.ai;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.example.bioskop.translation.core.srt.SubtitleCue;

public final class TranslationResponseValidator {
    private TranslationResponseValidator() {
    }

    public static void validateCoverage(List<SubtitleCue> cues, List<CueTranslation> translations) {
        Set<Integer> expected = cues.stream().map(SubtitleCue::id).collect(Collectors.toSet());
        Set<Integer> actual = translations.stream().map(CueTranslation::cueId).collect(Collectors.toSet());
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Translation cue coverage mismatch. expected=" + expected + ", actual=" + actual);
        }
        if (translations.stream().anyMatch(translation -> translation.text() == null || translation.text().isBlank())) {
            throw new IllegalArgumentException("Translation text must not be blank");
        }
    }
}
