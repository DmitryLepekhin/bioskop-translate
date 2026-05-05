package org.example.bioskop.translation.core.persistence;

import org.example.bioskop.translation.core.TranslationStatus;

final class TranslationEnumMapper {
    private TranslationEnumMapper() {
    }

    static int statusId(TranslationStatus status) {
        return switch (status) {
            case PENDING -> 1;
            case IN_PROGRESS -> 2;
            case COMPLETED -> 3;
            case FAILED -> 4;
        };
    }

    static TranslationStatus statusFromId(int id) {
        return switch (id) {
            case 1 -> TranslationStatus.PENDING;
            case 2 -> TranslationStatus.IN_PROGRESS;
            case 3 -> TranslationStatus.COMPLETED;
            case 4 -> TranslationStatus.FAILED;
            default -> throw new IllegalArgumentException("Unknown translation status id: " + id);
        };
    }
}
