package org.example.bioskop.translation.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.bioskop.translation.core.ai.CueTranslation;
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

    @Test
    void renewsLeaseWhileProviderCallIsBlocked() throws Exception {
        UUID sourceId = UUID.randomUUID();
        String sourceUri = "/source/slow-en.srt";
        storage.writeText(sourceUri, """
            1
            00:00:00,001 --> 00:00:01,000
            Hello
            """);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        JdbcTranslationService service = new JdbcTranslationService(repository, storage, request -> {
            providerStarted.countDown();
            try {
                if (!releaseProvider.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release provider");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while provider was blocked", e);
            }
            return request.cues().stream()
                .map(cue -> new CueTranslation(cue.id(), cue.text()))
                .toList();
        });
        service.requestTranslation(new TranslationRequest(sourceId, sourceUri, null, "en", "ru"));
        TranslationServiceProperties properties = new TranslationServiceProperties(
            1000,
            Duration.ofSeconds(8),
            Duration.ofMillis(250),
            Duration.ofMillis(50),
            5
        );

        try (TranslationWorker worker = new TranslationWorker(repository, service, properties)) {
            CompletableFuture<Boolean> execution = CompletableFuture.supplyAsync(worker::runOnce);
            assertTrue(providerStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(600);

            assertTrue(repository.claimNextAvailable(5, Duration.ofSeconds(1), UUID.randomUUID()).isEmpty());

            releaseProvider.countDown();
            assertTrue(execution.get(10, TimeUnit.SECONDS));
        } finally {
            releaseProvider.countDown();
        }
        assertEquals(TranslationStatus.COMPLETED, repository.findJob(sourceId, "ru").orElseThrow().status());
    }

    @Test
    void oldOwnerDoesNotWriteAfterLeaseIsReclaimedDuringProviderCall() {
        UUID sourceId = UUID.randomUUID();
        String sourceUri = "/source/lost-en.srt";
        String targetUri = "/source/lost-ru.srt";
        storage.writeText(sourceUri, """
            1
            00:00:00,001 --> 00:00:01,000
            Hello
            """);
        JdbcTranslationService service = new JdbcTranslationService(repository, storage, request -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider interrupted", e);
            }
            return request.cues().stream()
                .map(cue -> new CueTranslation(cue.id(), cue.text()))
                .toList();
        });
        service.requestTranslation(new TranslationRequest(sourceId, sourceUri, targetUri, "en", "ru"));
        UUID oldToken = UUID.randomUUID();
        TranslationJobRecord oldClaim = repository.claimNextAvailable(
            5,
            Duration.ofMillis(10),
            oldToken
        ).orElseThrow().job();
        UUID newToken = UUID.randomUUID();
        AtomicInteger checkpoints = new AtomicInteger();
        TranslationJobLease lease = new TranslationJobLease() {
            @Override
            public UUID token() {
                return oldToken;
            }

            @Override
            public void checkpoint() {
                if (checkpoints.incrementAndGet() == 4) {
                    assertTrue(repository.claimNextAvailable(5, Duration.ofSeconds(1), newToken).isPresent());
                    throw new LeaseLostException("Lease reclaimed by another worker");
                }
            }
        };

        assertThrows(LeaseLostException.class, () -> service.executeJob(oldClaim, lease));

        assertFalse(storage.exists(targetUri));
        TranslationJobRecord current = repository.findJob(sourceId, "ru").orElseThrow();
        assertEquals(newToken, current.leaseToken());
        assertEquals(TranslationStatus.IN_PROGRESS, current.status());
    }
}
