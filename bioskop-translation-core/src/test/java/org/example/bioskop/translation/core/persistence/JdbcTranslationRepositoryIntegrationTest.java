package org.example.bioskop.translation.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.example.bioskop.translation.core.TranslationStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcTranslationRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("bioskop_translation")
        .withUsername("bioskop_translation")
        .withPassword("bioskop_translation");

    private JdbcTranslationRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/translation-migration")
            .cleanDisabled(false)
            .load()
            .clean();

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/translation-migration")
            .load()
            .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcTranslationRepository(jdbc);
    }

    @Test
    void createsAndFindsTranslationJob() {
        UUID sourceId = UUID.randomUUID();

        TranslationJobRecord job = repository.createJob(
            sourceId,
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        TranslationJobAttemptRecord attempt = repository.createAttempt(job.id(), 1, TranslationStatus.IN_PROGRESS);
        repository.finishAttempt(attempt.id(), TranslationStatus.COMPLETED, null, null);
        repository.updateJobStatus(job.id(), TranslationStatus.COMPLETED, null, null);

        TranslationJobRecord savedJob = repository.findJob(sourceId, "ru").orElseThrow();
        List<TranslationJobAttemptRecord> attempts = repository.findAttempts(job.id());

        assertEquals(TranslationStatus.COMPLETED, savedJob.status());
        assertEquals("/source/exercise-ru.srt", savedJob.targetPath());
        assertNotNull(savedJob.completedAt());
        assertEquals(1, attempts.size());
        assertEquals(TranslationStatus.COMPLETED, attempts.get(0).status());
    }

    @Test
    void claimsPendingJobOnlyOnce() {
        UUID sourceId = UUID.randomUUID();
        repository.createJob(sourceId, "/source/exercise-en.srt", "en", "ru", "/source/exercise-ru.srt");

        TranslationJobRecord claimed = repository.claimNextPending(5).orElseThrow();

        assertEquals(TranslationStatus.IN_PROGRESS, claimed.status());
        assertEquals(1, claimed.attempts());
        assertEquals(List.of(), repository.claimNextPending(5).stream().toList());
    }

    @Test
    void failsPendingJobsThatExhaustedAttempts() {
        UUID sourceId = UUID.randomUUID();
        TranslationJobRecord job = repository.createJob(
            sourceId,
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        for (int i = 1; i <= 5; i++) {
            TranslationJobRecord claimed = repository.claimNextPending(5).orElseThrow();
            repository.updateJobStatus(claimed.id(), TranslationStatus.PENDING, null, null);
        }

        assertEquals(1, repository.failPendingExhausted(5));
        assertEquals(TranslationStatus.FAILED, repository.findJob(job.id()).orElseThrow().status());
    }

    @Test
    void reclaimsExpiredLeaseAndFencesOldOwner() {
        TranslationJobRecord created = repository.createJob(
            UUID.randomUUID(),
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        UUID oldToken = UUID.randomUUID();
        ClaimedTranslationJob first = repository.claimNextAvailable(5, Duration.ofMinutes(1), oldToken)
            .orElseThrow();
        TranslationJobAttemptRecord oldAttempt = repository.createAttempt(
            created.id(),
            first.job().attempts(),
            TranslationStatus.IN_PROGRESS
        );
        jdbc.update(
            "update translation_job set lease_expires_at = current_timestamp - interval '1 second' where id = ?",
            created.id()
        );

        UUID newToken = UUID.randomUUID();
        ClaimedTranslationJob reclaimed = repository.claimNextAvailable(5, Duration.ofMinutes(1), newToken)
            .orElseThrow();

        assertTrue(reclaimed.reclaimed());
        assertEquals(2, reclaimed.job().attempts());
        assertEquals(newToken, reclaimed.job().leaseToken());
        assertNotEquals(oldToken, reclaimed.job().leaseToken());
        assertFalse(repository.renewLease(created.id(), oldToken, Duration.ofMinutes(1)));
        assertTrue(repository.completeOwnedJob(created.id(), oldToken).isEmpty());
        assertTrue(repository.failOwnedJob(created.id(), oldToken, "OldOwner", "stale").isEmpty());
        assertEquals(1, repository.failOpenAttempts(created.id()));
        assertEquals(TranslationStatus.FAILED, repository.findAttempts(created.id()).get(0).status());
        repository.finishAttempt(oldAttempt.id(), TranslationStatus.COMPLETED, null, null);
        assertEquals(TranslationStatus.FAILED, repository.findAttempts(created.id()).get(0).status());
        assertTrue(repository.completeOwnedJob(created.id(), newToken).isPresent());
    }

    @Test
    void liveLeaseIgnoresOldUpdatedAt() {
        TranslationJobRecord created = repository.createJob(
            UUID.randomUUID(),
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        UUID token = UUID.randomUUID();
        repository.claimNextAvailable(5, Duration.ofMinutes(1), token).orElseThrow();
        jdbc.update(
            "update translation_job set updated_at = current_timestamp - interval '10 minutes' where id = ?",
            created.id()
        );

        assertTrue(repository.claimNextAvailable(5, Duration.ofMinutes(1), UUID.randomUUID()).isEmpty());
        assertTrue(repository.renewLease(created.id(), token, Duration.ofMinutes(1)));
    }

    @Test
    void expiredExhaustedLeaseBecomesFailed() {
        TranslationJobRecord created = repository.createJob(
            UUID.randomUUID(),
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        repository.claimNextAvailable(1, Duration.ofMinutes(1), UUID.randomUUID()).orElseThrow();
        jdbc.update(
            "update translation_job set lease_expires_at = current_timestamp - interval '1 second' where id = ?",
            created.id()
        );

        assertEquals(1, repository.failExhaustedAvailable(1));
        TranslationJobRecord failed = repository.findJob(created.id()).orElseThrow();
        assertEquals(TranslationStatus.FAILED, failed.status());
        assertEquals("MaxAttemptsExceeded", failed.errorCode());
        assertNull(failed.leaseToken());
    }
}
