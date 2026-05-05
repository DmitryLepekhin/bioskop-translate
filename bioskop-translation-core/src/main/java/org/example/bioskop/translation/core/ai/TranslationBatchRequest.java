package org.example.bioskop.translation.core.ai;

import java.util.List;
import org.example.bioskop.translation.core.TranslationFormat;
import org.example.bioskop.translation.core.context.TranslationContext;
import org.example.bioskop.translation.core.srt.SubtitleCue;

public record TranslationBatchRequest(
    String sourceLang,
    String targetLang,
    TranslationFormat format,
    List<SubtitleCue> cues,
    TranslationContext context
) {
}
