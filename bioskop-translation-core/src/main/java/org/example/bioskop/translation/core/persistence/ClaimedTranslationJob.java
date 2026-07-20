package org.example.bioskop.translation.core.persistence;

public record ClaimedTranslationJob(
    TranslationJobRecord job,
    boolean reclaimed
) {
}
