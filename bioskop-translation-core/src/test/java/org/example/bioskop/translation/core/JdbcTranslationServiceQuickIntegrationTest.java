package org.example.bioskop.translation.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import org.example.bioskop.translation.core.ai.FakeAiTranslationClient;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.persistence.TranslationJobRecord;
import org.example.bioskop.translation.core.storage.LocalFileTranslationStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcTranslationServiceQuickIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("bioskop_translation")
        .withUsername("bioskop_translation")
        .withPassword("bioskop_translation");

    @TempDir
    Path tempDir;

    private JdbcTranslationRepository repository;
    private LocalFileTranslationStorage storage;

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

        repository = new JdbcTranslationRepository(new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        )));
        storage = new LocalFileTranslationStorage(tempDir);
    }

    @Test
    void createsPendingJobWithGeneratedTargetPath() {
        UUID sourceId = UUID.randomUUID();
        JdbcTranslationService service = service();

        TranslationResponse response = service.requestTranslation(new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            null,
            "en",
            "ru"
        ));

        assertEquals(TranslationStatus.PENDING, response.status());
        assertEquals("/source/exercise-ru.srt", response.targetPath());
        assertEquals(TranslationStatus.PENDING, repository.findJob(sourceId, "ru").orElseThrow().status());
    }

    @Test
    void completedRequestReturnsCompletedWithoutRequeue() {
        UUID sourceId = UUID.randomUUID();
        repository.createJob(sourceId, "/source/exercise-en.srt", "en", "ru", "/source/exercise-ru.srt");
        repository.claimNextPending(5).orElseThrow();
        repository.updateJobStatus(repository.findJob(sourceId, "ru").orElseThrow().id(), TranslationStatus.COMPLETED, null, null);
        JdbcTranslationService service = service();

        TranslationResponse response = service.requestTranslation(new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            null,
            "en",
            "ru"
        ));

        assertEquals(TranslationStatus.COMPLETED, response.status());
        assertEquals(1, repository.findJob(sourceId, "ru").orElseThrow().attempts());
    }

    @Test
    void rejectsChangedTargetPathForExistingJob() {
        UUID sourceId = UUID.randomUUID();
        JdbcTranslationService service = service();
        service.requestTranslation(new TranslationRequest(sourceId, "/source/exercise-en.srt", null, "en", "ru"));

        assertThrows(IllegalArgumentException.class, () -> service.requestTranslation(new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            "/other/exercise-ru.srt",
            "en",
            "ru"
        )));
    }

    @Test
    void failedRequestReturnsFailedWithoutRequeue() {
        UUID sourceId = UUID.randomUUID();
        TranslationJobRecord job = repository.createJob(
            sourceId,
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        repository.claimNextPending(5).orElseThrow();
        repository.updateJobStatus(job.id(), TranslationStatus.FAILED, "ProviderError", "provider failed");
        TranslationJobRecord failed = repository.findJob(job.id()).orElseThrow();

        TranslationResponse response = service().requestTranslation(new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            null,
            "en",
            "ru"
        ));

        assertEquals(TranslationStatus.FAILED, response.status());
        assertEquals(failed, repository.findJob(job.id()).orElseThrow());
    }

    private JdbcTranslationService service() {
        return new JdbcTranslationService(
            repository,
            storage,
            new FakeAiTranslationClient(cue -> "ru:" + cue.text())
        );
    }
}
