package org.example.bioskop.translation.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.example.bioskop.translation.core.JdbcTranslationService;
import org.example.bioskop.translation.core.TranslationService;
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
