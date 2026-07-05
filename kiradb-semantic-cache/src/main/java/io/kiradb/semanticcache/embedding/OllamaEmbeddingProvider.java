package io.kiradb.semanticcache.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Neural embedding provider backed by a local <a href="https://ollama.com">Ollama</a>
 * server ({@code POST /api/embeddings}).
 *
 * <p>Unlike {@link LexicalEmbeddingProvider}, a neural model captures true
 * synonymy ("car" ≈ "automobile") because vectors come from a trained language
 * model, not word overlap. The tradeoff: it requires a running Ollama daemon
 * with the model pulled (e.g. {@code ollama pull nomic-embed-text}) and each
 * embed is an HTTP round trip (~5–30 ms locally).
 *
 * <p>Vectors are L2-normalized before being returned, honouring the
 * {@link EmbeddingProvider} contract regardless of what the model emits.
 *
 * <p>Thread-safe: {@link HttpClient} and {@link ObjectMapper} are both
 * safe for concurrent use.
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    /** Default Ollama base URL when none is configured. */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;

    /** Discovered on first successful embed; -1 until then. */
    private volatile int dimension = -1;

    /**
     * Create a provider pointing at an Ollama server.
     *
     * @param baseUrl base URL of the Ollama daemon, e.g. {@code http://localhost:11434}
     * @param model   the embedding model name, e.g. {@code nomic-embed-text}
     */
    public OllamaEmbeddingProvider(final String baseUrl, final String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public float[] embed(final String text) {
        String body;
        try {
            body = MAPPER.writeValueAsString(
                    MAPPER.createObjectNode().put("model", model).put("prompt", text));
        } catch (IOException e) {
            throw new EmbeddingException("Failed to serialize Ollama request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embeddings"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EmbeddingException(
                    "Cannot reach Ollama at " + baseUrl + " — is the daemon running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while calling Ollama", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new EmbeddingException(
                    "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }

        float[] vector = parseEmbedding(response.body());
        dimension = vector.length;
        return vector;
    }

    /**
     * Parse the {@code "embedding"} float array out of an Ollama JSON response
     * body and L2-normalize it. Package-private so it can be unit-tested
     * without a running Ollama daemon.
     *
     * @param json the raw response body, e.g. {@code {"embedding":[0.1,0.2,...]}}
     * @return the L2-normalized vector
     * @throws EmbeddingException if the body is not valid JSON or lacks an embedding array
     */
    static float[] parseEmbedding(final String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new EmbeddingException("Ollama response is not valid JSON", e);
        }
        JsonNode embedding = root.get("embedding");
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw new EmbeddingException(
                    "Ollama response missing 'embedding' array: " + abbreviate(json));
        }
        float[] vector = new float[embedding.size()];
        double sumSquares = 0.0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) embedding.get(i).asDouble();
            sumSquares += (double) vector[i] * vector[i];
        }
        if (sumSquares > 0.0) {
            double norm = Math.sqrt(sumSquares);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String id() {
        return "ollama:" + model;
    }

    private static String abbreviate(final String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
