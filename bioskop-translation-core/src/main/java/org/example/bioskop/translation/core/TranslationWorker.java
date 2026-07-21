package org.example.bioskop.translation.core;

import java.util.Optional;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.persistence.TranslationJobRecord;

public class TranslationWorker {
    private final JdbcTranslationRepository repository;
    private final JdbcTranslationService translationService;
    private final TranslationServiceProperties properties;

    public TranslationWorker(
        JdbcTranslationRepository repository,
        JdbcTranslationService translationService,
        TranslationServiceProperties properties
    ) {
        this.repository = repository;
        this.translationService = translationService;
        this.properties = properties;
    }

    public boolean runOnce() {
        repository.failPendingExhausted(properties.maxAttempts());
        Optional<TranslationJobRecord> pending = repository.claimNextPending(properties.maxAttempts());
        if (pending.isEmpty()) {
            return false;
        }
        translationService.executeJob(pending.get());
        return true;
    }
}
