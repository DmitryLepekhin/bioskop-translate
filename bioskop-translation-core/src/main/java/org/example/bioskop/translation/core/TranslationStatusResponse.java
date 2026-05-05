package org.example.bioskop.translation.core;

import java.util.UUID;

public record TranslationStatusResponse(
    UUID sourceTextId,
    String sourcePath,
    String targetPath,
    String sourceLang,
    String targetLang,
    TranslationStatus status,
    String message
) {
}
