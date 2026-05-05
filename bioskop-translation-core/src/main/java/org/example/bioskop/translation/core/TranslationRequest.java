package org.example.bioskop.translation.core;

import java.util.UUID;

public record TranslationRequest(
    UUID sourceTextId,
    String sourcePath,
    String targetPath,
    String sourceLang,
    String targetLang
) {
}
