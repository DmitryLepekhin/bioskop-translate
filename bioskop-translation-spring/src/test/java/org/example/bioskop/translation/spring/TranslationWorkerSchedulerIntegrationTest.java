package org.example.bioskop.translation.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.bioskop.translation.core.JdbcTranslationService;
import org.example.bioskop.translation.core.TranslationRequest;
import org.example.bioskop.translation.core.TranslationServiceProperties;
import org.example.bioskop.translation.core.TranslationWorker;
import org.example.bioskop.translation.core.ai.AiTranslationClient;
import org.example.bioskop.translation.core.ai.CueTranslation;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.storage.LocalFileTranslationStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TranslationWorkerSchedulerIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("bioskop_translation")
        .withUsername("bioskop_translation")
        .withPassword("bioskop_translation");

    @TempDir
    Path tempDir;

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
        storage = new LocalFileTranslationStorage(tempDir);
    }

    @Test
    void oneContextDoesNotOverlapProviderCalls() {
        BlockingProvider provider = new BlockingProvider(2);
        TranslationWorker worker = worker(provider);
        requestTranslation("/source/first-en.srt", "ru", provider);
        requestTranslation("/source/second-en.srt", "de", provider);

        contextRunner(worker).run(context -> {
            try {
                assertTrue(provider.firstStarted.await(10, TimeUnit.SECONDS));
                assertFalse(provider.allStarted.await(300, TimeUnit.MILLISECONDS));
                assertEquals(1, provider.maximumActive.get());

                provider.release.countDown();
                assertTrue(provider.allStarted.await(10, TimeUnit.SECONDS));
                assertTrue(provider.allCompleted.await(10, TimeUnit.SECONDS));
                awaitCompletedJobs(2);
                assertEquals(1, provider.maximumActive.get());
            } finally {
                provider.release.countDown();
            }
        });
    }

    @Test
    void twoContextsProcessDifferentJobsConcurrently() {
        BlockingProvider provider = new BlockingProvider(2);
        requestTranslation("/source/first-en.srt", "ru", provider);
        requestTranslation("/source/second-en.srt", "de", provider);
        TranslationWorker firstWorker = worker(provider);
        TranslationWorker secondWorker = worker(provider);

        contextRunner(firstWorker).run(firstContext ->
            contextRunner(secondWorker).run(secondContext -> {
                try {
                    assertTrue(provider.allStarted.await(10, TimeUnit.SECONDS));
                    assertEquals(2, provider.maximumActive.get());
                    provider.release.countDown();
                    assertTrue(provider.allCompleted.await(10, TimeUnit.SECONDS));
                    awaitCompletedJobs(2);
                } finally {
                    provider.release.countDown();
                }
            })
        );
    }

    private ApplicationContextRunner contextRunner(TranslationWorker worker) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TranslationWorkerScheduler.class))
            .withBean(TranslationWorker.class, () -> worker)
            .withPropertyValues(
                "bioskop.translation.worker.enabled=true",
                "bioskop.translation.worker.poll-delay=PT0.05S"
            );
    }

    private TranslationWorker worker(AiTranslationClient provider) {
        JdbcTranslationRepository repository = repository();
        JdbcTranslationService service = new JdbcTranslationService(repository, storage, provider);
        return new TranslationWorker(
            repository,
            service,
            new TranslationServiceProperties(
                1000,
                Duration.ofSeconds(8),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                5
            )
        );
    }

    private void requestTranslation(String sourcePath, String targetLang, AiTranslationClient provider) {
        storage.writeText(sourcePath, """
            1
            00:00:00,001 --> 00:00:01,000
            Hello
            """);
        new JdbcTranslationService(repository(), storage, provider).requestTranslation(new TranslationRequest(
            UUID.randomUUID(),
            sourcePath,
            null,
            "en",
            targetLang
        ));
    }

    private JdbcTranslationRepository repository() {
        return new JdbcTranslationRepository(new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        )));
    }

    private void awaitCompletedJobs(int expected) throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer completed = jdbc.queryForObject(
                "select count(*) from translation_job where status = 3",
                Integer.class
            );
            if (completed != null && completed == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for completed translation jobs");
    }

    private static final class BlockingProvider implements AiTranslationClient {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch allStarted;
        private final CountDownLatch allCompleted;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();

        private BlockingProvider(int expectedCalls) {
            allStarted = new CountDownLatch(expectedCalls);
            allCompleted = new CountDownLatch(expectedCalls);
        }

        @Override
        public java.util.List<CueTranslation> translateBatch(
            org.example.bioskop.translation.core.ai.TranslationBatchRequest request
        ) {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            firstStarted.countDown();
            allStarted.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release provider");
                }
                return request.cues().stream()
                    .map(cue -> new CueTranslation(cue.id(), cue.text()))
                    .toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider interrupted", e);
            } finally {
                active.decrementAndGet();
                allCompleted.countDown();
            }
        }
    }
}
