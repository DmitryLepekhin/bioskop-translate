package org.example.bioskop.translation.core;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.bioskop.translation.core.persistence.ClaimedTranslationJob;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;

public class TranslationWorker implements AutoCloseable {
    private final JdbcTranslationRepository repository;
    private final JdbcTranslationService translationService;
    private final TranslationServiceProperties properties;
    private final TranslationTelemetry telemetry;
    private final ScheduledExecutorService heartbeatExecutor;
    private final boolean ownsHeartbeatExecutor;

    public TranslationWorker(
        JdbcTranslationRepository repository,
        JdbcTranslationService translationService,
        TranslationServiceProperties properties
    ) {
        this(
            repository,
            translationService,
            properties,
            TranslationTelemetry.noop(),
            newHeartbeatExecutor(),
            true
        );
    }

    public TranslationWorker(
        JdbcTranslationRepository repository,
        JdbcTranslationService translationService,
        TranslationServiceProperties properties,
        TranslationTelemetry telemetry
    ) {
        this(repository, translationService, properties, telemetry, newHeartbeatExecutor(), true);
    }

    public TranslationWorker(
        JdbcTranslationRepository repository,
        JdbcTranslationService translationService,
        TranslationServiceProperties properties,
        TranslationTelemetry telemetry,
        ScheduledExecutorService heartbeatExecutor
    ) {
        this(repository, translationService, properties, telemetry, heartbeatExecutor, false);
    }

    private TranslationWorker(
        JdbcTranslationRepository repository,
        JdbcTranslationService translationService,
        TranslationServiceProperties properties,
        TranslationTelemetry telemetry,
        ScheduledExecutorService heartbeatExecutor,
        boolean ownsHeartbeatExecutor
    ) {
        this.repository = repository;
        this.translationService = translationService;
        this.properties = properties;
        this.telemetry = telemetry;
        this.heartbeatExecutor = heartbeatExecutor;
        this.ownsHeartbeatExecutor = ownsHeartbeatExecutor;
    }

    public boolean runOnce() {
        repository.failExhaustedAvailable(properties.maxAttempts());
        Optional<ClaimedTranslationJob> claimed = repository.claimNextAvailable(
            properties.maxAttempts(),
            properties.leaseDuration(),
            UUID.randomUUID()
        );
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedTranslationJob claim = claimed.get();
        if (claim.reclaimed()) {
            repository.failOpenAttempts(claim.job().id());
        }
        telemetry.jobClaimed(claim.job().attempts() > 1, claim.reclaimed());
        try (ActiveLease lease = new ActiveLease(claim.job().id(), claim.job().leaseToken())) {
            try {
                translationService.executeJob(claim.job(), lease);
                telemetry.jobCompleted();
            } catch (LeaseLostException e) {
                return true;
            } catch (RuntimeException e) {
                telemetry.jobFailed();
                throw e;
            }
            return true;
        }
    }

    @Override
    public void close() {
        if (ownsHeartbeatExecutor) {
            heartbeatExecutor.shutdownNow();
        }
    }

    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bioskop-translation-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    private final class ActiveLease implements TranslationJobLease, AutoCloseable {
        private final UUID jobId;
        private final UUID token;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final ScheduledFuture<?> heartbeat;

        private ActiveLease(UUID jobId, UUID token) {
            this.jobId = jobId;
            this.token = token;
            long intervalMillis = properties.heartbeatInterval().toMillis();
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                this::renewInBackground,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
            );
        }

        @Override
        public UUID token() {
            return token;
        }

        @Override
        public void checkpoint() {
            if (lost.get() || !repository.renewLease(jobId, token, properties.leaseDuration())) {
                lost.set(true);
                throw new LeaseLostException("Translation job lease is no longer owned");
            }
        }

        private void renewInBackground() {
            try {
                if (!repository.renewLease(jobId, token, properties.leaseDuration())) {
                    lost.set(true);
                }
            } catch (RuntimeException e) {
                lost.set(true);
            }
        }

        @Override
        public void close() {
            heartbeat.cancel(false);
        }
    }
}
