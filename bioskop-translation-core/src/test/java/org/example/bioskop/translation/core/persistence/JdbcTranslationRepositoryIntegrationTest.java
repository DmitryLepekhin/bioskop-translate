package org.example.bioskop.translation.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        repository = new JdbcTranslationRepository(new JdbcTemplate(dataSource));
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
    void manuallyRecoversInProgressJobAndClosesInterruptedAttempt() {
        TranslationJobRecord job = repository.createJob(
            UUID.randomUUID(), "/source/en.srt", "en", "ru", "/source/ru.srt"
        );
        TranslationJobRecord claimed = repository.claimNextPending(5).orElseThrow();
        repository.createAttempt(claimed.id(), claimed.attempts(), TranslationStatus.IN_PROGRESS);

        assertTrue(repository.requeueInProgressForManualRecovery(job.id(), 5));

        TranslationJobRecord recovered = repository.findJob(job.id()).orElseThrow();
        TranslationJobAttemptRecord attempt = repository.findAttempts(job.id()).get(0);
        assertEquals(TranslationStatus.PENDING, recovered.status());
        assertEquals(1, recovered.attempts());
        assertEquals(TranslationStatus.FAILED, attempt.status());
        assertEquals("ManualRecovery", attempt.errorCode());
        assertNotNull(attempt.finishedAt());
        assertTrue(!repository.requeueInProgressForManualRecovery(job.id(), 5));
    }

    @Test
    void manualRecoveryDoesNotChangeOtherStatesOrExhaustedJob() {
        TranslationJobRecord pending = repository.createJob(
            UUID.randomUUID(), "/source/pending-en.srt", "en", "ru", "/source/pending-ru.srt"
        );
        assertTrue(!repository.requeueInProgressForManualRecovery(pending.id(), 5));

        TranslationJobRecord claimed = repository.claimNextPending(1).orElseThrow();
        assertTrue(!repository.requeueInProgressForManualRecovery(claimed.id(), 1));
        assertEquals(TranslationStatus.IN_PROGRESS, repository.findJob(claimed.id()).orElseThrow().status());
    }
}
