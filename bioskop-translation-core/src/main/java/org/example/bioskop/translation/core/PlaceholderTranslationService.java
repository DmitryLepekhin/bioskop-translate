package org.example.bioskop.translation.core;

import java.util.Objects;
import java.util.UUID;

public class PlaceholderTranslationService implements TranslationService {
    @Override
    public TranslationResponse requestTranslation(TranslationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateSourceId(request.sourceTextId());
        validateLanguage(request.targetLang(), "targetLang");
        return new TranslationResponse(
            request.sourceTextId(),
            request.sourcePath(),
            request.targetPath(),
            request.sourceLang(),
            request.targetLang(),
            TranslationStatus.PENDING,
            "Translation implementation is not configured yet"
        );
    }

    @Override
    public TranslationStatusResponse getStatus(UUID sourceId, String targetLang) {
        validateSourceId(sourceId);
        validateLanguage(targetLang, "targetLang");
        return new TranslationStatusResponse(
            sourceId,
            null,
            null,
            null,
            targetLang,
            null,
            "Translation implementation is not configured yet"
        );
    }

    private static void validateSourceId(UUID sourceId) {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceTextId must not be null");
        }
    }

    private static void validateLanguage(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
