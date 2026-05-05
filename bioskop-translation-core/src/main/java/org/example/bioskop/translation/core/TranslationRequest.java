package org.example.bioskop.translation.core;

import java.util.Set;
import java.util.UUID;

public record TranslationRequest(
    UUID sourceTextId,
    String sourceLang,
    String targetLang,
    Set<TranslationFormat> format,
    boolean immediate
) {
}
