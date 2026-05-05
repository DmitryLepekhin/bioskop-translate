package org.example.bioskop.translation.core;

public record TranslationOutputStatus(
    TranslationFormat format,
    TranslationStatus status,
    String uri,
    String message
) {
}
