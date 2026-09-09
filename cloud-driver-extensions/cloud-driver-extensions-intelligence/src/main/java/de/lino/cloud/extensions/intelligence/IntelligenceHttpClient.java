package de.lino.cloud.extensions.intelligence;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import de.lino.cloud.api.intelligence.IntelligenceDocument;
import de.lino.cloud.api.intelligence.SemanticMatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * A thin HTTP client for the {@code cloud-driver-intelligence} Python service - the semantic-search
 * counterpart to {@code ClamAvClient}'s role for {@code clamd}, and deliberately just as dumb: it
 * serializes a request, sends it, and parses a response. It never decides what is embeddable, never
 * inspects content, and above all never interprets the service's answer as an access decision (see
 * {@link de.lino.cloud.api.intelligence.IntelligenceService}'s own security-invariant section).
 *
 * <p>Built on the JDK's own {@link HttpClient} and Gson - see this module's {@code pom.xml} comment
 * for why no dedicated HTTP/JSON dependency was added.
 *
 * <h2>Authentication</h2>
 *
 * Every request carries the configured shared secret in an {@code X-Internal-Secret} header. This
 * is <b>not</b> a substitute for network isolation: the Python service is expected to bind to
 * loopback (or an internal container network) only, exactly as {@code clamd} does, and this header
 * exists to stop a same-host process from trivially reading or poisoning the vector store, not to
 * make the service safe to expose publicly.
 */
final class IntelligenceHttpClient {

    /** Header every request authenticates with - matches the Python service's own middleware. */
    private static final String SHARED_SECRET_HEADER = "X-Internal-Secret";

    private final URI indexEndpoint;
    private final URI searchEndpoint;
    private final String baseUrl;
    private final String sharedSecret;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    IntelligenceHttpClient(final String host, final int port, final String sharedSecret, final Duration timeout) {
        this.baseUrl = "http://" + host + ":" + port;
        this.indexEndpoint = URI.create(this.baseUrl + "/index");
        this.searchEndpoint = URI.create(this.baseUrl + "/search");
        this.sharedSecret = sharedSecret;
        this.timeout = timeout;
        // HTTP/1.1 is pinned deliberately - do not "modernise" this to the default.
        // The JDK's HttpClient defaults to Version.HTTP_2, which over plaintext http:// means it
        // attempts an h2c upgrade: the first request goes out as HTTP/1.1 carrying
        // "Connection: Upgrade, HTTP2-Settings" and "Upgrade: h2c". uvicorn (this service's ASGI
        // server) does not implement h2c, and rather than declining the upgrade cleanly it drops
        // the request body - so FastAPI answered every single POST with
        // 422 {"detail":[{"type":"missing","loc":["body"]}]} while the body was demonstrably being
        // written by this client (confirmed by capturing the real outgoing request: correct
        // Content-Type, Content-Length: 106, 106 bytes received). Found only by running the Java
        // bridge against the real Python service; a stub HTTP server reading the body raw never
        // reproduces it.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /** The {@code POST /index} request body - {@code contentBase64} is {@code null} for a file whose bytes this server never held. */
    private record IndexRequest(String fileId, String ownerUserId, String fileName, String contentType, String contentBase64) {
    }

    /** The {@code POST /search} request body. */
    private record SearchRequest(String queryText, Collection<String> candidateFileIds, int limit) {
    }

    /** One entry of {@code POST /search}'s response array. */
    private record SearchHit(String fileId, double score) {
    }

    /**
     * Embeds and stores {@code document}, replacing any vector previously stored under the same
     * file id.
     *
     * @throws IOException if the connection itself fails (refused, timed out, reset, ...)
     * @throws IntelligenceServiceException if the service answered with a non-2xx status
     */
    void index(final IntelligenceDocument document) throws IOException, IntelligenceServiceException {
        final String contentBase64 = document.content() == null ? null
                : Base64.getEncoder().encodeToString(document.content());
        final IndexRequest body = new IndexRequest(document.storedFileId(), document.authUserId(),
                document.fileName(), document.contentType(), contentBase64);
        send(this.indexEndpoint, "POST", this.gson.toJson(body));
    }

    /**
     * Removes {@code storedFileId}'s vector. A file that was never indexed is not an error - the
     * Python service answers {@code 204} either way, matching {@code ObjectStorageService#deleteObject}'s
     * own idempotent-on-absence contract elsewhere in this codebase.
     *
     * @throws IOException if the connection itself fails
     * @throws IntelligenceServiceException if the service answered with a non-2xx status
     */
    void remove(final String storedFileId) throws IOException, IntelligenceServiceException {
        final URI endpoint = URI.create(this.baseUrl + "/index/" + java.net.URLEncoder.encode(storedFileId, StandardCharsets.UTF_8));
        send(endpoint, "DELETE", null);
    }

    /**
     * Ranks {@code candidateFileIds} against {@code queryText}.
     *
     * <p>The returned ids are <b>untrusted</b> - this method deliberately does not verify that each
     * one was actually a member of {@code candidateFileIds}, because doing so here would invite the
     * belief that the caller's own re-check is therefore unnecessary. That re-check
     * ({@code CloudUserService#semanticSearch}, stage 2) is the real guarantee.
     *
     * @throws IOException if the connection itself fails
     * @throws IntelligenceServiceException if the service answered with a non-2xx status or an unparseable body
     */
    List<SemanticMatch> search(final String queryText, final Collection<String> candidateFileIds, final int limit)
            throws IOException, IntelligenceServiceException {
        final String responseBody = send(this.searchEndpoint, "POST",
                this.gson.toJson(new SearchRequest(queryText, candidateFileIds, limit)));
        final SearchHit[] hits;
        try {
            hits = this.gson.fromJson(responseBody, SearchHit[].class);
        } catch (final JsonSyntaxException malformed) {
            throw new IntelligenceServiceException("Unparseable /search response: " + malformed.getMessage());
        }
        if (hits == null) return List.of();
        return java.util.Arrays.stream(hits)
                .filter(hit -> hit != null && hit.fileId() != null)
                .map(hit -> new SemanticMatch(hit.fileId(), hit.score()))
                .toList();
    }

    /** Health probe backing {@code CloudIntelligenceExtension}'s startup log line - never throws, just reports reachability. */
    boolean isHealthy() {
        try {
            send(URI.create(this.baseUrl + "/health"), "GET", null);
            return true;
        } catch (final IOException | IntelligenceServiceException | RuntimeException unreachable) {
            return false;
        }
    }

    private String send(final URI endpoint, final String method, final String jsonBody) throws IOException, IntelligenceServiceException {
        final HttpRequest.BodyPublisher publisher = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);

        final HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(this.timeout)
                .header(SHARED_SECRET_HEADER, this.sharedSecret)
                .method(method, publisher);
        if (jsonBody != null) request.header("Content-Type", "application/json");

        final HttpResponse<String> response;
        try {
            response = this.httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + endpoint, interrupted);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IntelligenceServiceException(method + " " + endpoint.getPath() + " returned " + response.statusCode()
                    + ": " + response.body());
        }
        return response.body();
    }

}
