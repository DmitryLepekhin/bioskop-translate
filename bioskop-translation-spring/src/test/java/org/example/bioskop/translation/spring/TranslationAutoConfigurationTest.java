package org.example.bioskop.translation.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.example.bioskop.translation.core.JdbcTranslationService;
import org.example.bioskop.translation.core.TranslationService;
import org.example.bioskop.translation.core.TranslationTelemetry;
import org.example.bioskop.translation.core.ai.FakeAiTranslationClient;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.storage.LocalFileTranslationStorage;
import org.example.bioskop.translation.core.storage.TranslationStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class TranslationAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TranslationMetricsAutoConfiguration.class,
            TranslationAutoConfiguration.class
        ))
        .withBean(FakeAiTranslationClient.class, () -> new FakeAiTranslationClient(cue -> cue.text()))
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:translation-test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "bioskop.translation.storage.type=local",
            "bioskop.translation.storage.local-root=build/test-translation-storage"
        );

    @Test
    void createsJdbcTranslationServiceFromProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JdbcTranslationRepository.class);
            assertThat(context).hasSingleBean(TranslationStorage.class);
            assertThat(context).hasSingleBean(JdbcTranslationService.class);
            assertThat(context).hasSingleBean(TranslationService.class);
            assertThat(context.getBean(TranslationStorage.class)).isInstanceOf(LocalFileTranslationStorage.class);
        });
    }

    @Test
    void configuresS3EndpointOverride() {
        TranslationProperties properties = propertiesWithS3Endpoint("https://fra1.digitaloceanspaces.com");

        try (S3Client client = new TranslationAutoConfiguration().s3Client(properties)) {
            assertThat(client.serviceClientConfiguration().endpointOverride())
                .contains(URI.create("https://fra1.digitaloceanspaces.com"));
        }
    }

    @Test
    void leavesS3EndpointOverrideUnsetWhenNotConfigured() {
        TranslationProperties properties = propertiesWithS3Endpoint("");

        try (S3Client client = new TranslationAutoConfiguration().s3Client(properties)) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void bindsProviderTimeoutRetryAndLeaseProperties() {
        contextRunner.withPropertyValues(
            "bioskop.translation.openai.connect-timeout=PT3S",
            "bioskop.translation.openai.request-timeout=PT20S",
            "bioskop.translation.openai.max-attempts=4",
            "bioskop.translation.openai.initial-backoff=PT0.1S",
            "bioskop.translation.openai.max-backoff=PT2S",
            "bioskop.translation.openai.jitter-factor=0.1",
            "bioskop.translation.openai.endpoint=https://example.test/responses",
            "bioskop.translation.worker.lease-duration=PT30S",
            "bioskop.translation.worker.heartbeat-interval=PT5S"
        ).run(context -> {
            TranslationProperties properties = context.getBean(TranslationProperties.class);
            assertThat(properties.openai().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.openai().requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.openai().maxAttempts()).isEqualTo(4);
            assertThat(properties.openai().initialBackoff()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.openai().maxBackoff()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.openai().jitterFactor()).isEqualTo(0.1);
            assertThat(properties.openai().endpoint()).isEqualTo("https://example.test/responses");
            assertThat(properties.worker().leaseDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.worker().heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void emitsMetricsWhenRegistryIsPresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        contextRunner.withBean(SimpleMeterRegistry.class, () -> registry).run(context -> {
            assertThat(context).hasSingleBean(TranslationTelemetry.class);
            TranslationTelemetry telemetry = context.getBean(TranslationTelemetry.class);

            telemetry.jobClaimed(true, true);
            telemetry.jobCompleted();
            telemetry.jobFailed();
            telemetry.providerCall(Duration.ofMillis(25), TranslationTelemetry.ProviderOutcome.TIMEOUT);

            assertThat(registry.counter("bioskop.translation.jobs.claimed").count()).isEqualTo(1);
            assertThat(registry.counter("bioskop.translation.jobs.retried").count()).isEqualTo(1);
            assertThat(registry.counter("bioskop.translation.jobs.reclaimed").count()).isEqualTo(1);
            assertThat(registry.counter("bioskop.translation.jobs.completed").count()).isEqualTo(1);
            assertThat(registry.counter("bioskop.translation.jobs.failed").count()).isEqualTo(1);
            assertThat(registry.counter(
                "bioskop.translation.provider.calls",
                "outcome",
                "timeout"
            ).count()).isEqualTo(1);
            assertThat(registry.timer(
                "bioskop.translation.provider.duration",
                "outcome",
                "timeout"
            ).count()).isEqualTo(1);
        });
    }

    private TranslationProperties propertiesWithS3Endpoint(String endpoint) {
        return new TranslationProperties(
            new TranslationProperties.Storage(
                TranslationProperties.StorageType.S3,
                null,
                "translations",
                "fra1",
                null,
                endpoint
            ),
            null,
            null,
            null,
            0
        );
    }
}
