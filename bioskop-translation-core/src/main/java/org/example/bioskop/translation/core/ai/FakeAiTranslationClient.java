package org.example.bioskop.translation.core.ai;

import java.util.List;
import java.util.function.Function;
import org.example.bioskop.translation.core.srt.SubtitleCue;

public class FakeAiTranslationClient implements AiTranslationClient {
    private final Function<SubtitleCue, String> translator;

    public FakeAiTranslationClient(Function<SubtitleCue, String> translator) {
        this.translator = translator;
    }

    @Override
    public List<CueTranslation> translateBatch(TranslationBatchRequest request) {
        return request.cues().stream()
            .map(cue -> new CueTranslation(cue.id(), translator.apply(cue)))
            .toList();
    }
}
