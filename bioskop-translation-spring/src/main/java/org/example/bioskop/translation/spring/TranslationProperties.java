package org.example.bioskop.translation.spring;

import java.nio.file.Path;
import java.time.Duration;
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
        openai = openai == null ? new OpenAi(null, null, null, null, null, 0, null, null, null) : openai;
        quick = quick == null ? new Quick(0, null) : quick;
        worker = worker == null ? new Worker(false, null, null, null) : worker;
        Duration leaseDuration = worker.leaseDuration() == null
            ? openai.requestTimeout().plusSeconds(30)
            : worker.leaseDuration();
        Duration heartbeatInterval = worker.heartbeatInterval() == null
            ? Duration.ofSeconds(20)
            : worker.heartbeatInterval();
        if (leaseDuration.compareTo(openai.requestTimeout()) <= 0) {
            throw new IllegalArgumentException(
                "worker.leaseDuration " + leaseDuration
                    + " must exceed openai.requestTimeout " + openai.requestTimeout()
            );
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("worker.heartbeatInterval must be shorter than worker.leaseDuration");
        }
        worker = new Worker(worker.enabled(), worker.pollDelay(), leaseDuration, heartbeatInterval);
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
        String model,
        String endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Double jitterFactor
    ) {
        public OpenAi {
            model = model == null || model.isBlank() ? "gpt-4.1-mini" : model;
            endpoint = endpoint == null || endpoint.isBlank()
                ? "https://api.openai.com/v1/responses"
                : endpoint;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            initialBackoff = initialBackoff == null ? Duration.ofMillis(250) : initialBackoff;
            maxBackoff = maxBackoff == null ? Duration.ofSeconds(5) : maxBackoff;
            jitterFactor = jitterFactor == null ? 0.2 : jitterFactor;
            if (connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("openai.connectTimeout must be positive");
            }
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("openai.requestTimeout must be positive");
            }
            if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
                throw new IllegalArgumentException("OpenAI backoffs must not be negative");
            }
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("openai.maxBackoff must not be shorter than initialBackoff");
            }
            if (jitterFactor < 0 || jitterFactor > 1) {
                throw new IllegalArgumentException("openai.jitterFactor must be between 0 and 1");
            }
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
        Duration leaseDuration,
        Duration heartbeatInterval
    ) {
        public Worker {
            pollDelay = pollDelay == null ? Duration.ofSeconds(5) : pollDelay;
            if (pollDelay.isZero() || pollDelay.isNegative()) {
                throw new IllegalArgumentException("worker.pollDelay must be positive");
            }
            if (leaseDuration != null && (leaseDuration.isZero() || leaseDuration.isNegative())) {
                throw new IllegalArgumentException("worker.leaseDuration must be positive");
            }
            if (heartbeatInterval != null && (heartbeatInterval.isZero() || heartbeatInterval.isNegative())) {
                throw new IllegalArgumentException("worker.heartbeatInterval must be positive");
            }
        }
    }
}
