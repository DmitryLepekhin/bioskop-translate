package org.example.bioskop.translation.core;

import java.util.List;
import java.util.UUID;

public record TranslationStatusResponse(
    UUID sourceTextId,
    String targetLang,
    List<TranslationOutputStatus> outputs
) {
}
