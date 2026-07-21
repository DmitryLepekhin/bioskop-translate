package org.example.bioskop.translation.spring;

import org.example.bioskop.translation.core.JdbcTranslationService;
import org.example.bioskop.translation.core.TranslationService;
import org.example.bioskop.translation.core.TranslationServiceProperties;
import org.example.bioskop.translation.core.TranslationWorker;
import org.example.bioskop.translation.core.ai.AiTranslationClient;
import org.example.bioskop.translation.core.ai.OpenAiTranslationClient;
import org.example.bioskop.translation.core.coordination.PostgresAdvisoryWorkerCoordinator;
import org.example.bioskop.translation.core.coordination.TranslationWorkerCoordinator;
import org.example.bioskop.translation.core.context.TranslationContextLoader;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.srt.SrtParser;
import org.example.bioskop.translation.core.srt.SrtWriter;
import org.example.bioskop.translation.core.storage.LocalFileTranslationStorage;
import org.example.bioskop.translation.core.storage.S3TranslationStorage;
import org.example.bioskop.translation.core.storage.TranslationStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import javax.sql.DataSource;

@AutoConfiguration
@EnableConfigurationProperties(TranslationProperties.class)
public class TranslationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    JdbcTranslationRepository jdbcTranslationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcTranslationRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    TranslationStorage translationStorage(TranslationProperties properties) {
        TranslationProperties.Storage storage = properties.storage();
        if (storage.type() == TranslationProperties.StorageType.LOCAL) {
            return new LocalFileTranslationStorage(storage.localRoot());
        }
        return new S3TranslationStorage(s3Client(properties), storage.s3Bucket());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "bioskop.translation.openai",
        name = "api-key"
    )
    AiTranslationClient aiTranslationClient(TranslationProperties properties) {
        return new OpenAiTranslationClient(
            properties.openai().apiKey(),
            properties.openai().model()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    TranslationServiceProperties translationServiceProperties(TranslationProperties properties) {
        return new TranslationServiceProperties(
            properties.quick().immediateMaxChars(),
            properties.quick().immediateTimeout(),
            properties.maxAttempts()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    TranslationContextLoader translationContextLoader(
        TranslationStorage storage
    ) {
        return new TranslationContextLoader(storage);
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcTranslationService jdbcTranslationService(
        JdbcTranslationRepository repository,
        TranslationStorage storage,
        AiTranslationClient aiClient,
        TranslationServiceProperties serviceProperties,
        TranslationContextLoader contextLoader
    ) {
        return new JdbcTranslationService(
            repository,
            storage,
            aiClient,
            new SrtParser(),
            new SrtWriter(),
            serviceProperties,
            contextLoader
        );
    }

    @Bean
    @ConditionalOnMissingBean(TranslationService.class)
    TranslationService translationService(JdbcTranslationService jdbcTranslationService) {
        return jdbcTranslationService;
    }

    @Bean
    @ConditionalOnBean(JdbcTranslationService.class)
    @ConditionalOnProperty(
        prefix = "bioskop.translation.worker",
        name = "enabled",
        havingValue = "true"
    )
    @ConditionalOnMissingBean
    TranslationWorker translationWorker(
        JdbcTranslationRepository repository,
        JdbcTranslationService translationService,
        TranslationServiceProperties serviceProperties
    ) {
        return new TranslationWorker(repository, translationService, serviceProperties);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "bioskop.translation.worker",
        name = "enabled",
        havingValue = "true"
    )
    @ConditionalOnMissingBean
    TranslationWorkerCoordinator translationWorkerCoordinator(
        DataSource dataSource,
        TranslationProperties properties
    ) {
        return new PostgresAdvisoryWorkerCoordinator(dataSource, properties.worker().advisoryLockKey());
    }

    S3Client s3Client(TranslationProperties properties) {
        TranslationProperties.Storage storage = properties.storage();
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(storage.s3Region()));
        if (storage.s3Profile() != null && !storage.s3Profile().isBlank()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(storage.s3Profile()));
        }
        if (StringUtils.hasText(storage.s3Endpoint())) {
            builder.endpointOverride(URI.create(storage.s3Endpoint()));
        }
        return builder.build();
    }
}
