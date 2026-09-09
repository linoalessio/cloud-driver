package de.lino.cloud.extensions.intelligence;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import de.lino.cloud.api.intelligence.DuplicateGroup;
import de.lino.cloud.api.intelligence.IntelligenceDocument;
import de.lino.cloud.api.intelligence.SemanticMatch;
import de.lino.cloud.api.intelligence.TagSuggestion;

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
    private final URI duplicatesEndpoint;
    private final URI tagsEndpoint;
    private final URI healthEndpoint;
    private final String baseUrl;
    private final String sharedSecret;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    IntelligenceHttpClient(final String host, final int port, final String sharedSecret, final Duration timeout) {
        this.baseUrl = "http://" + host + ":" + port;
        this.indexEndpoint = URI.create(this.baseUrl + "/index");
        this.searchEndpoint = URI.create(this.baseUrl + "/search");
        this.duplicatesEndpoint = URI.create(this.baseUrl + "/duplicates");
        this.tagsEndpoint = URI.create(this.baseUrl + "/tags");
        this.healthEndpoint = URI.create(this.baseUrl + "/health");
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

    /** The {@code PATCH /index/{fileId}/owner} request body. */
    private record UpdateOwnerRequest(String ownerUserId) {
    }

    /** The {@code POST /duplicates} request body. */
    private record DuplicatesRequest(Collection<String> candidateFileIds, double minimumSimilarity, int limit) {
    }

    /** One entry of {@code POST /duplicates}' response array. */
    private record DuplicateGroupResponse(List<String> storedFileIds, double similarity) {
    }

    /** The {@code POST /tags} request body. */
    private record TagsRequest(String fileId, int limit) {
    }

    /** One entry of {@code POST /tags}' response array. */
    private record TagSuggestionResponse(String tag, double confidence) {
    }

    /** The {@code GET /health} response body - only the two fields this client actually acts on. */
    private record HealthResponse(String status, boolean embeddingsAvailable) {
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

    /**
     * Refreshes {@code storedFileId}'s recorded owner without re-embedding its content.
     *
     * @throws IOException if the connection itself fails
     * @throws IntelligenceServiceException if the service answered with a non-2xx status
     */
    void updateOwner(final String storedFileId, final String ownerAuthUserId) throws IOException, IntelligenceServiceException {
        final URI endpoint = URI.create(this.baseUrl + "/index/"
                + java.net.URLEncoder.encode(storedFileId, StandardCharsets.UTF_8) + "/owner");
        send(endpoint, "PATCH", this.gson.toJson(new UpdateOwnerRequest(ownerAuthUserId)));
    }

    /**
     * Groups {@code candidateFileIds} into near-duplicate sets.
     *
     * <p>Like {@link #search}, the returned ids are <b>untrusted</b> and are deliberately not
     * verified here against {@code candidateFileIds} - the caller's own re-check is the real
     * guarantee, and validating here would invite the belief that it is not needed.
     *
     * @throws IOException if the connection itself fails
     * @throws IntelligenceServiceException if the service answered with a non-2xx status or an unparseable body
     */
    List<DuplicateGroup> findDuplicates(final Collection<String> candidateFileIds, final double minimumSimilarity, final int limit)
            throws IOException, IntelligenceServiceException {
        final String responseBody = send(this.duplicatesEndpoint, "POST",
                this.gson.toJson(new DuplicatesRequest(candidateFileIds, minimumSimilarity, limit)));
        final DuplicateGroupResponse[] groups;
        try {
            groups = this.gson.fromJson(responseBody, DuplicateGroupResponse[].class);
        } catch (final JsonSyntaxException malformed) {
            throw new IntelligenceServiceException("Unparseable /duplicates response: " + malformed.getMessage());
        }
        if (groups == null) return List.of();
        return java.util.Arrays.stream(groups)
                .filter(group -> group != null && group.storedFileIds() != null && group.storedFileIds().size() >= 2)
                .map(group -> new DuplicateGroup(List.copyOf(group.storedFileIds()), group.similarity()))
                .toList();
    }

    /**
     * Asks for descriptive labels for one already-indexed file.
     *
     * @throws IOException if the connection itself fails
     * @throws IntelligenceServiceException if the service answered with a non-2xx status or an unparseable body
     */
    List<TagSuggestion> suggestTags(final String storedFileId, final int limit) throws IOException, IntelligenceServiceException {
        final String responseBody = send(this.tagsEndpoint, "POST", this.gson.toJson(new TagsRequest(storedFileId, limit)));
        final TagSuggestionResponse[] suggestions;
        try {
            suggestions = this.gson.fromJson(responseBody, TagSuggestionResponse[].class);
        } catch (final JsonSyntaxException malformed) {
            throw new IntelligenceServiceException("Unparseable /tags response: " + malformed.getMessage());
        }
        if (suggestions == null) return List.of();
        return java.util.Arrays.stream(suggestions)
                .filter(suggestion -> suggestion != null && suggestion.tag() != null)
                .map(suggestion -> new TagSuggestion(suggestion.tag(), suggestion.confidence()))
                .toList();
    }

    /**
     * Health probe backing {@code CloudIntelligenceExtension}'s startup log line and {@code
     * IntelligenceService#isServiceHealthy()} - never throws, just reports reachability.
     *
     * <p>Requires the service to report a usable embedding backend, not merely to answer.
     * A service that is up but cannot embed produces results indistinguishable from "nothing
     * matched" - reporting that as healthy would defeat the entire point of probing.
     */
    boolean isHealthy() {
        try {
            final HealthResponse health = this.gson.fromJson(send(this.healthEndpoint, "GET", null), HealthResponse.class);
            return health != null && "ok".equalsIgnoreCase(health.status()) && health.embeddingsAvailable();
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
