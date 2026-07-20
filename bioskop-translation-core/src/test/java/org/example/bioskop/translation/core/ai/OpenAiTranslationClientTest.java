package org.example.bioskop.translation.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.example.bioskop.translation.core.TranslationFormat;
import org.example.bioskop.translation.core.TranslationTelemetry;
import org.example.bioskop.translation.core.context.TranslationContext;
import org.example.bioskop.translation.core.srt.SubtitleCue;
import org.junit.jupiter.api.Test;

class OpenAiTranslationClientTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void translateBatchSendsStringInput() throws IOException {
        FakeHttpClient httpClient = new FakeHttpClient(200, """
            {"output":[{"content":[{"text":"{\\"translations\\":[{\\"cueId\\":1,\\"text\\":\\"Bonjour\\"},{\\"cueId\\":2,\\"text\\":\\"Monde\\"}]}"}]}]}
            """);
        OpenAiTranslationClient client = newClient(httpClient);

        List<CueTranslation> translations = client.translateBatch(request());

        JsonNode body = OBJECT_MAPPER.readTree(httpClient.capturedBody());
        assertTrue(body.path("input").isTextual());

        JsonNode input = OBJECT_MAPPER.readTree(body.path("input").asText());
        assertEquals("en", input.path("sourceLang").asText());
        assertEquals("fr", input.path("targetLang").asText());
        assertEquals("QUICK", input.path("format").asText());
        assertEquals(1, input.path("cues").get(0).path("cueId").asInt());
        assertEquals("Hello", input.path("cues").get(0).path("text").asText());
        assertEquals("Alice", input.path("cues").get(0).path("speaker").asText());
        assertEquals(List.of(
            new CueTranslation(1, "Bonjour"),
            new CueTranslation(2, "Monde")
        ), translations);
    }

    @Test
    void translateBatchParsesOutputText() {
        FakeHttpClient httpClient = new FakeHttpClient(200, """
            {"output_text":"{\\"translations\\":[{\\"cueId\\":1,\\"text\\":\\"Bonjour\\"},{\\"cueId\\":2,\\"text\\":\\"Monde\\"}]}"}
            """);
        OpenAiTranslationClient client = newClient(httpClient);

        List<CueTranslation> translations = client.translateBatch(request());

        assertEquals(List.of(
            new CueTranslation(1, "Bonjour"),
            new CueTranslation(2, "Monde")
        ), translations);
    }

    @Test
    void translateBatchRejectsMissingCueCoverage() {
        FakeHttpClient httpClient = new FakeHttpClient(200, """
            {"output_text":"{\\"translations\\":[{\\"cueId\\":1,\\"text\\":\\"Bonjour\\"}]}"}
            """);
        OpenAiTranslationClient client = newClient(httpClient);

        assertThrows(IllegalArgumentException.class, () -> client.translateBatch(request()));
    }

    @Test
    void translateBatchThrowsUsefulErrorForBadStatus() {
        FakeHttpClient httpClient = new FakeHttpClient(400, "{\"error\":\"bad input\"}");
        OpenAiTranslationClient client = newClient(httpClient);

        UncheckedIOException exception = assertThrows(
            UncheckedIOException.class,
            () -> client.translateBatch(request())
        );
        IOException cause = assertInstanceOf(IOException.class, exception.getCause());
        assertTrue(cause.getMessage().contains("OpenAI API returned status 400"));
        assertTrue(cause.getMessage().contains("bad input"));
    }

    @Test
    void permanentStatusIsNotRetried() {
        FakeHttpClient httpClient = new FakeHttpClient(400, "{\"error\":\"bad input\"}");
        OpenAiClientSettings defaults = OpenAiClientSettings.defaults();
        OpenAiTranslationClient client = new OpenAiTranslationClient(
            httpClient,
            OBJECT_MAPPER,
            "test-key",
            "test-model",
            new OpenAiClientSettings(
                URI.create("https://example.test/responses"),
                defaults.connectTimeout(),
                defaults.requestTimeout(),
                3,
                Duration.ZERO,
                Duration.ZERO,
                0
            ),
            TranslationTelemetry.noop(),
            duration -> {
            },
            () -> 0.5,
            Clock.systemUTC()
        );

        assertThrows(UncheckedIOException.class, () -> client.translateBatch(request()));
        assertEquals(1, httpClient.callCount());
    }

    @Test
    void throttlingRetriesAndHonorsRetryAfter() {
        FakeHttpClient httpClient = new FakeHttpClient(
            List.of(429, 200),
            List.of(
                "{\"error\":\"slow down\"}",
                "{\"output_text\":\"{\\\"translations\\\":[{\\\"cueId\\\":1,\\\"text\\\":\\\"Bonjour\\\"},{\\\"cueId\\\":2,\\\"text\\\":\\\"Monde\\\"}]}\"}"
            ),
            Map.of("Retry-After", List.of("2"))
        );
        List<Duration> sleeps = new ArrayList<>();
        List<TranslationTelemetry.ProviderOutcome> outcomes = new ArrayList<>();
        TranslationTelemetry telemetry = new TranslationTelemetry() {
            @Override
            public void providerCall(Duration duration, ProviderOutcome outcome) {
                outcomes.add(outcome);
            }
        };
        OpenAiClientSettings defaults = OpenAiClientSettings.defaults();
        OpenAiTranslationClient client = new OpenAiTranslationClient(
            httpClient,
            OBJECT_MAPPER,
            "test-key",
            "test-model",
            new OpenAiClientSettings(
                URI.create("https://example.test/responses"),
                defaults.connectTimeout(),
                defaults.requestTimeout(),
                2,
                Duration.ofMillis(10),
                Duration.ofSeconds(5),
                0
            ),
            telemetry,
            sleeps::add,
            () -> 0.5,
            Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)
        );

        assertEquals(2, client.translateBatch(request()).size());
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
        assertEquals(List.of(
            TranslationTelemetry.ProviderOutcome.THROTTLED,
            TranslationTelemetry.ProviderOutcome.SUCCESS
        ), outcomes);
        assertEquals(2, httpClient.callCount());
    }

    private static OpenAiTranslationClient newClient(FakeHttpClient httpClient) {
        return new OpenAiTranslationClient(
            httpClient,
            OBJECT_MAPPER,
            URI.create("https://example.test/responses"),
            "test-key",
            "test-model",
            1,
            Duration.ZERO
        );
    }

    private static TranslationBatchRequest request() {
        return new TranslationBatchRequest(
            "en",
            "fr",
            TranslationFormat.QUICK,
            List.of(
                new SubtitleCue(1, 0, 1000, "Hello", "Alice"),
                new SubtitleCue(2, 1000, 2000, "World", null)
            ),
            TranslationContext.empty()
        );
    }

    private static final class FakeHttpClient extends HttpClient {
        private final List<Integer> statusCodes;
        private final List<String> responseBodies;
        private final Map<String, List<String>> responseHeaders;
        private String capturedBody;
        private int callCount;

        private FakeHttpClient(int statusCode, String responseBody) {
            this(List.of(statusCode), List.of(responseBody), Map.of());
        }

        private FakeHttpClient(
            List<Integer> statusCodes,
            List<String> responseBodies,
            Map<String, List<String>> responseHeaders
        ) {
            this.statusCodes = statusCodes;
            this.responseBodies = responseBodies;
            this.responseHeaders = responseHeaders;
        }

        private String capturedBody() {
            return capturedBody;
        }

        private int callCount() {
            return callCount;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException {
            capturedBody = readBody(request);
            int resultIndex = Math.min(callCount, statusCodes.size() - 1);
            callCount++;
            return new FakeHttpResponse<>(
                request,
                statusCodes.get(resultIndex),
                responseBodies.get(resultIndex),
                responseHeaders
            );
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            try {
                return CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, responseBodyHandler);
        }

        private static String readBody(HttpRequest request) throws IOException {
            BodyCollector collector = new BodyCollector();
            request.bodyPublisher()
                .orElseThrow(() -> new IOException("Request did not contain a body"))
                .subscribe(collector);
            return collector.body();
        }
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            output.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        private String body() throws IOException {
            try {
                completed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading request body", e);
            }
            if (error != null) {
                throw new IOException("Failed to read request body", error);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeHttpResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final int statusCode;
        private final String body;
        private final Map<String, List<String>> headers;

        private FakeHttpResponse(
            HttpRequest request,
            int statusCode,
            String body,
            Map<String, List<String>> headers
        ) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            return (T) body;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headers, (name, value) -> true);
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
