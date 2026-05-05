package org.example.bioskop.translation.core.context;

import java.util.List;
import java.util.Map;

public record TranslationContext(
    String notes,
    List<CharacterInfo> characters,
    Map<Integer, String> speakersByCueId
) {
    public static TranslationContext empty() {
        return new TranslationContext(null, List.of(), Map.of());
    }
}
