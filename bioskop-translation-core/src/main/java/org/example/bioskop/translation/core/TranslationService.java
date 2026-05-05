package org.example.bioskop.translation.core;

import java.util.UUID;

public interface TranslationService {
    TranslationResponse requestTranslation(TranslationRequest request);

    TranslationStatusResponse getStatus(UUID sourceId, String targetLang);
}
