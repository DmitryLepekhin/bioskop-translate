package org.example.bioskop.translation.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.bioskop.translation.core.ai.AiTranslationClient;
import org.example.bioskop.translation.core.ai.CueTranslation;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.persistence.TranslationJobRecord;
import org.example.bioskop.translation.core.storage.LocalFileTranslationStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcTranslationServiceConcurrencyIntegrationTest {
    private static final int CALLER_COUNT = 8;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("bioskop_translation")
        .withUsername("bioskop_translation")
        .withPassword("bioskop_translation");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
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
        jdbc = jdbcTemplate();
        storage = new LocalFileTranslationStorage(tempDir);
    }

    @Test
    void concurrentFirstRequestsReturnOneCanonicalJob() throws Exception {
        UUID sourceId = UUID.randomUUID();
        TranslationRequest request = request(sourceId);

        List<TranslationResponse> responses = requestConcurrently(request, noOpClient());

        assertEquals(CALLER_COUNT, responses.size());
        assertTrue(responses.stream().allMatch(responses.get(0)::equals));
        assertEquals(TranslationStatus.PENDING, responses.get(0).status());
        assertEquals(1, countJobs(sourceId));
        TranslationJobRecord canonical = repository().findJob(sourceId, "ru").orElseThrow();
        assertEquals(0, canonical.attempts());
        assertEquals("/source/exercise-en.srt", canonical.sourcePath());
        assertEquals("/source/exercise-ru.srt", canonical.targetPath());
    }

    @Test
    void concurrentRequestsDoNotChangeExistingStates() throws Exception {
        for (TranslationStatus status : TranslationStatus.values()) {
            UUID sourceId = UUID.randomUUID();
            JdbcTranslationRepository seedRepository = repository();
            TranslationJobRecord job = seedRepository.createJob(
                sourceId,
                "/source/exercise-en.srt",
                "en",
                "ru",
                "/source/exercise-ru.srt"
            );
            if (status == TranslationStatus.COMPLETED) {
                seedRepository.updateJobStatus(job.id(), status, null, null);
            } else if (status == TranslationStatus.FAILED) {
                seedRepository.updateJobStatus(job.id(), status, "ProviderError", "provider failed");
            } else if (status == TranslationStatus.IN_PROGRESS) {
                seedRepository.updateJobStatus(job.id(), status, null, null);
            }
            TranslationJobRecord before = seedRepository.findJob(job.id()).orElseThrow();

            List<TranslationResponse> responses = requestConcurrently(request(sourceId), noOpClient());

            assertTrue(responses.stream().allMatch(response -> response.status() == status));
            assertEquals(before, seedRepository.findJob(job.id()).orElseThrow());
            assertEquals(1, countJobs(sourceId));
        }
    }

    @Test
    void concurrentWorkersExecuteCanonicalJobOnce() throws Exception {
        UUID sourceId = UUID.randomUUID();
        String sourcePath = "/source/exercise-en.srt";
        storage.writeText(sourcePath, """
            1
            00:00:00,001 --> 00:00:01,000
            Hello
            """);
        AtomicInteger providerCalls = new AtomicInteger();
        AiTranslationClient client = request -> {
            providerCalls.incrementAndGet();
            return request.cues().stream()
                .map(cue -> new CueTranslation(cue.id(), "ru:" + cue.text()))
                .toList();
        };
        requestConcurrently(request(sourceId), client);

        ExecutorService executor = Executors.newFixedThreadPool(CALLER_COUNT);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Boolean>> workers = new ArrayList<>();
            for (int i = 0; i < CALLER_COUNT; i++) {
                JdbcTranslationRepository workerRepository = repository();
                JdbcTranslationService service = service(workerRepository, client);
                TranslationWorker worker = new TranslationWorker(
                    workerRepository,
                    service,
                    TranslationServiceProperties.defaults()
                );
                workers.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return worker.runOnce();
                }, executor));
            }
            start.countDown();
            List<Boolean> results = workers.stream().map(CompletableFuture::join).toList();

            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, providerCalls.get());
            TranslationJobRecord job = repository().findJob(sourceId, "ru").orElseThrow();
            assertEquals(TranslationStatus.COMPLETED, job.status());
            assertEquals(1, job.attempts());
            assertEquals(1, repository().findAttempts(job.id()).size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void incompatibleRepeatedRequestDoesNotChangeCanonicalPaths() {
        UUID sourceId = UUID.randomUUID();
        JdbcTranslationService service = service(repository(), noOpClient());
        service.requestTranslation(request(sourceId));
        TranslationJobRecord canonical = repository().findJob(sourceId, "ru").orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> service.requestTranslation(new TranslationRequest(
            sourceId,
            "/other/source-en.srt",
            "/other/source-ru.srt",
            "en",
            "ru"
        )));

        assertEquals(canonical, repository().findJob(sourceId, "ru").orElseThrow());
    }

    @Test
    void unrelatedConstraintViolationIsNotSwallowed() {
        UUID invalidSourceId = UUID.randomUUID();
        JdbcTranslationRepository repository = repository();

        assertThrows(DataIntegrityViolationException.class, () -> repository.createOrGetJob(
            invalidSourceId,
            null,
            "en",
            "ru",
            "/source/exercise-ru.srt"
        ));

        UUID validSourceId = UUID.randomUUID();
        TranslationJobRecord job = repository.createOrGetJob(
            validSourceId,
            "/source/exercise-en.srt",
            "en",
            "ru",
            "/source/exercise-ru.srt"
        );
        assertFalse(repository.findJob(job.id()).isEmpty());
    }

    private List<TranslationResponse> requestConcurrently(
        TranslationRequest request,
        AiTranslationClient client
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CALLER_COUNT);
        try {
            CountDownLatch ready = new CountDownLatch(CALLER_COUNT);
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<TranslationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < CALLER_COUNT; i++) {
                JdbcTranslationService service = service(repository(), client);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(start);
                    return service.requestTranslation(request);
                }, executor));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private JdbcTranslationService service(
        JdbcTranslationRepository repository,
        AiTranslationClient client
    ) {
        return new JdbcTranslationService(repository, storage, client);
    }

    private JdbcTranslationRepository repository() {
        return new JdbcTranslationRepository(jdbcTemplate());
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ));
    }

    private int countJobs(UUID sourceId) {
        return jdbc.queryForObject(
            "select count(*) from translation_job where source_text_id = ?",
            Integer.class,
            sourceId
        );
    }

    private static TranslationRequest request(UUID sourceId) {
        return new TranslationRequest(
            sourceId,
            "/source/exercise-en.srt",
            "/source/exercise-ru.srt",
            "en",
            "ru"
        );
    }

    private static AiTranslationClient noOpClient() {
        return request -> request.cues().stream()
            .map(cue -> new CueTranslation(cue.id(), cue.text()))
            .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent test", e);
        }
    }
}
