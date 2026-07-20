package org.example.bioskop.translation.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.example.bioskop.translation.core.TranslationTelemetry;

public class OpenAiTranslationClient implements AiTranslationClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final OpenAiClientSettings settings;
    private final TranslationTelemetry telemetry;
    private final Sleeper sleeper;
    private final DoubleSupplier jitterSource;
    private final Clock clock;

    public OpenAiTranslationClient(String apiKey, String model) {
        this(apiKey, model, OpenAiClientSettings.defaults(), TranslationTelemetry.noop());
    }

    public OpenAiTranslationClient(
        String apiKey,
        String model,
        OpenAiClientSettings settings,
        TranslationTelemetry telemetry
    ) {
        this(
            HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build(),
            new ObjectMapper(),
            apiKey,
            model,
            settings,
            telemetry,
            OpenAiTranslationClient::sleep,
            () -> ThreadLocalRandom.current().nextDouble(),
            Clock.systemUTC()
        );
    }

    public OpenAiTranslationClient(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        URI endpoint,
        String apiKey,
        String model,
        int maxAttempts,
        Duration retryBackoff
    ) {
        OpenAiClientSettings defaults = OpenAiClientSettings.defaults();
        OpenAiClientSettings compatibilitySettings = new OpenAiClientSettings(
            endpoint,
            defaults.connectTimeout(),
            defaults.requestTimeout(),
            maxAttempts,
            retryBackoff,
            retryBackoff,
            0
        );
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.settings = compatibilitySettings;
        this.telemetry = TranslationTelemetry.noop();
        this.sleeper = OpenAiTranslationClient::sleep;
        this.jitterSource = () -> 0.5;
        this.clock = Clock.systemUTC();
    }

    OpenAiTranslationClient(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        String apiKey,
        String model,
        OpenAiClientSettings settings,
        TranslationTelemetry telemetry,
        Sleeper sleeper,
        DoubleSupplier jitterSource,
        Clock clock
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.settings = settings;
        this.telemetry = telemetry;
        this.sleeper = sleeper;
        this.jitterSource = jitterSource;
        this.clock = clock;
    }

    @Override
    public List<CueTranslation> translateBatch(TranslationBatchRequest request) {
        try {
            String body = objectMapper.writeValueAsString(new OpenAiRequest(
                model,
                buildInstructions(request),
                buildInput(request)
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(settings.endpoint())
                .timeout(settings.requestTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            String responseBody = sendWithRetry(httpRequest);
            List<CueTranslation> translations = parseTranslations(responseBody);
            TranslationResponseValidator.validateCoverage(request.cues(), translations);
            return translations;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to call OpenAI translation API", e);
        }
    }

    private String sendWithRetry(HttpRequest request) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            long startedAt = System.nanoTime();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                recordProviderCall(startedAt, TranslationTelemetry.ProviderOutcome.PERMANENT_FAILURE);
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling OpenAI translation API", e);
            } catch (HttpTimeoutException e) {
                recordProviderCall(startedAt, TranslationTelemetry.ProviderOutcome.TIMEOUT);
                lastException = e;
                if (attempt == settings.maxAttempts()) {
                    throw e;
                }
                sleepBackoff(attempt, HttpHeaders.of(java.util.Map.of(), (name, value) -> true));
                continue;
            } catch (IOException e) {
                recordProviderCall(startedAt, TranslationTelemetry.ProviderOutcome.TRANSIENT_FAILURE);
                lastException = e;
                if (attempt == settings.maxAttempts()) {
                    throw e;
                }
                sleepBackoff(attempt, HttpHeaders.of(java.util.Map.of(), (name, value) -> true));
                continue;
            }

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                recordProviderCall(startedAt, TranslationTelemetry.ProviderOutcome.SUCCESS);
                return response.body();
            }
            boolean transientStatus = isTransientStatus(response.statusCode());
            recordProviderCall(
                startedAt,
                response.statusCode() == 429
                    ? TranslationTelemetry.ProviderOutcome.THROTTLED
                    : transientStatus
                        ? TranslationTelemetry.ProviderOutcome.TRANSIENT_FAILURE
                        : TranslationTelemetry.ProviderOutcome.PERMANENT_FAILURE
            );
            if (!transientStatus || attempt == settings.maxAttempts()) {
                throw statusException(response);
            }
            sleepBackoff(attempt, response.headers());
        }
        throw lastException == null ? new IOException("OpenAI request failed") : lastException;
    }

    private boolean isTransientStatus(int statusCode) {
        return statusCode == 429
            || statusCode == 500
            || statusCode == 502
            || statusCode == 503
            || statusCode == 504;
    }

    private IOException statusException(HttpResponse<String> response) {
        return new IOException(
            "OpenAI API returned status " + response.statusCode() + ": " + response.body()
        );
    }

    private void sleepBackoff(int attempt, HttpHeaders headers) throws IOException {
        sleeper.sleep(retryDelay(attempt, headers));
    }

    private Duration retryDelay(int attempt, HttpHeaders headers) {
        long initialMillis = settings.initialBackoff().toMillis();
        int shift = Math.min(attempt - 1, 30);
        long exponentialMillis = initialMillis > (Long.MAX_VALUE >> shift)
            ? Long.MAX_VALUE
            : initialMillis << shift;
        long boundedMillis = Math.min(exponentialMillis, settings.maxBackoff().toMillis());
        double jitter = 1 + ((jitterSource.getAsDouble() * 2) - 1) * settings.jitterFactor();
        long jitteredMillis = Math.max(0, Math.round(boundedMillis * jitter));
        long retryAfterMillis = parseRetryAfter(headers).map(Duration::toMillis).orElse(0L);
        return Duration.ofMillis(Math.min(
            Math.max(jitteredMillis, retryAfterMillis),
            settings.maxBackoff().toMillis()
        ));
    }

    private Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
            } catch (NumberFormatException ignored) {
                try {
                    ZonedDateTime retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                    Duration delay = Duration.between(clock.instant(), retryAt.toInstant());
                    return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
                } catch (DateTimeParseException invalidDate) {
                    return Optional.empty();
                }
            }
        });
    }

    private void recordProviderCall(long startedAt, TranslationTelemetry.ProviderOutcome outcome) {
        telemetry.providerCall(Duration.ofNanos(System.nanoTime() - startedAt), outcome);
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during OpenAI retry backoff", e);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws IOException;
    }

    private String buildInstructions(TranslationBatchRequest request) {
        String style = request.format().name().equals("ADAPTED")
            ? "Adapt tone, idioms, humor, and character voice while preserving cue boundaries."
            : "Translate faithfully and stay close to the original subtitle text.";
        return """
            Translate subtitle cues from %s to %s. %s
            Return only JSON with shape {"translations":[{"cueId":1,"text":"..."}]}.
            Do not add, remove, merge, split, or renumber cues.
            """.formatted(request.sourceLang(), request.targetLang(), style);
    }

    private String buildInput(TranslationBatchRequest request) throws IOException {
        List<CueInput> cues = request.cues().stream()
            .map(cue -> new CueInput(cue.id(), cue.text(), cue.speaker()))
            .toList();
        return objectMapper.writeValueAsString(new TranslationInput(
            request.sourceLang(),
            request.targetLang(),
            request.format().name(),
            cues
        ));
    }

    private List<CueTranslation> parseTranslations(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        String text = extractOutputText(root);
        JsonNode translations = objectMapper.readTree(text).path("translations");
        List<CueTranslation> result = new ArrayList<>();
        for (JsonNode node : translations) {
            result.add(new CueTranslation(node.path("cueId").asInt(), node.path("text").asText()));
        }
        return result;
    }

    private String extractOutputText(JsonNode root) {
        if (root.hasNonNull("output_text")) {
            return root.path("output_text").asText();
        }
        JsonNode output = root.path("output");
        for (JsonNode item : output) {
            for (JsonNode content : item.path("content")) {
                if (content.hasNonNull("text")) {
                    return content.path("text").asText();
                }
            }
        }
        throw new IllegalArgumentException("OpenAI response did not contain output text");
    }

    private record OpenAiRequest(String model, String instructions, String input) {
    }

    private record TranslationInput(String sourceLang, String targetLang, String format, List<CueInput> cues) {
    }

    private record CueInput(int cueId, String text, String speaker) {
    }
}
