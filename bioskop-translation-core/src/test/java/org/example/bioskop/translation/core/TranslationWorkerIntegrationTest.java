package org.example.bioskop.translation.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class TranslationWorkerIntegrationTest {
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
    void processesAsyncTranslation() {
        UUID sourceId = UUID.randomUUID();
        String sourceUri = "/source/input-en.srt";
        String targetUri = "/source/input-ru.srt";
        storage.writeText(sourceUri, """
            1
            00:00:00,001 --> 00:00:01,000
            Hello
            """);
        JdbcTranslationService service = new JdbcTranslationService(
            repository,
            storage,
            new FakeAiTranslationClient(cue -> "translated " + cue.text())
        );

        TranslationResponse response = service.requestTranslation(new TranslationRequest(
            sourceId,
            sourceUri,
            targetUri,
            "en",
            "ru"
        ));
        TranslationWorker worker = new TranslationWorker(repository, service, TranslationServiceProperties.defaults());

        assertEquals(TranslationStatus.PENDING, response.status());
        assertTrue(worker.runOnce());

        TranslationJobRecord job = repository.findJob(sourceId, "ru").orElseThrow();
        assertEquals(TranslationStatus.COMPLETED, job.status());
        assertEquals(1, job.attempts());
        assertTrue(storage.readText(targetUri).contains("translated Hello"));
    }

    @Test
    void marksExistingTargetAsCompletedWithoutTranslating() {
        UUID sourceId = UUID.randomUUID();
        String targetUri = "/source/input-ru.srt";
        storage.writeText(targetUri, "already translated");
        JdbcTranslationService service = new JdbcTranslationService(
            repository,
            storage,
            new FakeAiTranslationClient(cue -> {
                throw new AssertionError("AI should not be called");
            })
        );
        service.requestTranslation(new TranslationRequest(sourceId, "/source/input-en.srt", targetUri, "en", "ru"));

        assertTrue(new TranslationWorker(repository, service, TranslationServiceProperties.defaults()).runOnce());

        assertEquals(TranslationStatus.COMPLETED, repository.findJob(sourceId, "ru").orElseThrow().status());
    }
}
