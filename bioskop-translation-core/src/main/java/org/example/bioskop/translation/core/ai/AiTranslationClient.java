package org.example.bioskop.translation.core.ai;

import java.util.List;

public interface AiTranslationClient {
    List<CueTranslation> translateBatch(TranslationBatchRequest request);
}
