package org.example.bioskop.translation.spring;

import java.nio.file.Path;
import java.time.Duration;
import org.example.bioskop.translation.core.coordination.PostgresAdvisoryWorkerCoordinator;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bioskop.translation")
public record TranslationProperties(
    Storage storage,
    OpenAi openai,
    Quick quick,
    Worker worker,
    int maxAttempts
) {
    public TranslationProperties {
        storage = storage == null ? new Storage(null, null, null, null, null, null) : storage;
        openai = openai == null ? new OpenAi(null, null) : openai;
        quick = quick == null ? new Quick(0, null) : quick;
        worker = worker == null ? new Worker(false, null, null) : worker;
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
    }

    public record Storage(
        StorageType type,
        Path localRoot,
        String s3Bucket,
        String s3Region,
        String s3Profile,
        String s3Endpoint
    ) {
        public Storage {
            type = type == null ? StorageType.LOCAL : type;
            localRoot = localRoot == null ? Path.of("build/bioskop-translation-storage") : localRoot;
            s3Region = s3Region == null || s3Region.isBlank() ? "us-east-1" : s3Region;
        }
    }

    public enum StorageType {
        LOCAL,
        S3
    }

    public record OpenAi(
        String apiKey,
        String model
    ) {
        public OpenAi {
            model = model == null || model.isBlank() ? "gpt-4.1-mini" : model;
        }
    }

    public record Quick(
        int immediateMaxChars,
        Duration immediateTimeout
    ) {
        public Quick {
            immediateMaxChars = immediateMaxChars <= 0 ? 1000 : immediateMaxChars;
            immediateTimeout = immediateTimeout == null ? Duration.ofSeconds(8) : immediateTimeout;
        }
    }

    public record Worker(
        boolean enabled,
        Duration pollDelay,
        Long advisoryLockKey
    ) {
        public Worker {
            pollDelay = pollDelay == null ? Duration.ofSeconds(5) : pollDelay;
            advisoryLockKey = advisoryLockKey == null
                ? PostgresAdvisoryWorkerCoordinator.DEFAULT_LOCK_KEY
                : advisoryLockKey;
        }
    }
}
