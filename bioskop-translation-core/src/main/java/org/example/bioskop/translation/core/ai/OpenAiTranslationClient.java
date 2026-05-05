package org.example.bioskop.translation.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OpenAiTranslationClient implements AiTranslationClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxAttempts;
    private final Duration retryBackoff;

    public OpenAiTranslationClient(String apiKey, String model) {
        this(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            URI.create("https://api.openai.com/v1/responses"),
            apiKey,
            model,
            3,
            Duration.ofMillis(250)
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
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public List<CueTranslation> translateBatch(TranslationBatchRequest request) {
        try {
            String body = objectMapper.writeValueAsString(new OpenAiRequest(
                model,
                buildInstructions(request),
                buildInput(request)
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
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
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                if (!isTransientStatus(response.statusCode()) || attempt == maxAttempts) {
                    throw new IOException("OpenAI API returned status " + response.statusCode() + ": " + response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling OpenAI translation API", e);
            } catch (IOException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw e;
                }
            }
            sleepBackoff();
        }
        throw lastException == null ? new IOException("OpenAI request failed") : lastException;
    }

    private boolean isTransientStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void sleepBackoff() throws IOException {
        try {
            Thread.sleep(retryBackoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during OpenAI retry backoff", e);
        }
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
