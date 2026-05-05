package org.example.bioskop.translation.core;

import java.util.List;
import java.util.UUID;

public record TranslationResponse(
    UUID sourceTextId,
    String sourceLang,
    String targetLang,
    List<TranslationOutputStatus> outputs
) {
}
