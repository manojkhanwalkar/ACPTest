package com.example.acptest.acp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Minimal ACP server on the JDK's built-in HTTP server. Implements the ACP REST endpoints needed for
 * discovery and synchronous runs:
 * <ul>
 *     <li>{@code GET  /ping}</li>
 *     <li>{@code GET  /agents} and {@code GET /agents/{name}}</li>
 *     <li>{@code POST /runs} with {@code "mode": "sync"}</li>
 * </ul>
 * Run requests larger than {@link #MAX_REQUEST_BYTES} are refused with HTTP 413, more than
 * {@link #MAX_CONCURRENT_RUNS} runs at once with HTTP 429, and session ids must be 1-128 characters of
 * {@code [A-Za-z0-9._:-]}. Errors returned to callers never carry internal exception messages; those are logged
 * with a reference id.
 */
public final class AcpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpServer.class);

    /** Largest accepted {@code POST /runs} body. */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /** Runs executing at once; further runs are refused with HTTP 429, which bounds LLM cost and threads. */
    public static final int MAX_CONCURRENT_RUNS = 64;
    private static final Pattern VALID_SESSION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final Map<String, AcpAgent> agents = new LinkedHashMap<>();
    private final Semaphore running = new Semaphore(MAX_CONCURRENT_RUNS);
    private final HttpServer http;

    /** Starts a server on localhost; pass port 0 to pick a free port. */
    public AcpServer(int port, AcpAgent... hostedAgents) throws IOException {
        for (AcpAgent agent : hostedAgents) {
            agents.put(agent.manifest().name(), agent);
        }
        http = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        http.createContext("/ping", ex -> handle(ex, "GET", () -> sendJson(ex, 200, Map.of())));
        http.createContext("/agents", ex -> handle(ex, "GET", () -> agents(ex)));
        http.createContext("/runs", ex -> handle(ex, "POST", () -> createRun(ex)));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        log.info("ACP server listening on {} hosting {}", baseUri(), agents.keySet());
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + http.getAddress().getPort());
    }

    private void agents(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/agents") || path.equals("/agents/")) {
            List<Acp.AgentManifest> manifests = agents.values().stream().map(AcpAgent::manifest).toList();
            sendJson(ex, 200, new Acp.AgentsListResponse(manifests));
            return;
        }
        AcpAgent agent = agents.get(path.substring("/agents/".length()));
        if (agent == null) {
            sendJson(ex, 404, new Acp.Error("not_found", "Agent not found"));
        } else {
            sendJson(ex, 200, agent.manifest());
        }
    }

    private void createRun(HttpExchange ex) throws IOException {
        Instant createdAt = Instant.now();
        Acp.RunCreateRequest request;
        byte[] body = ex.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            sendJson(ex, 413, new Acp.Error("invalid_input", "Request larger than " + MAX_REQUEST_BYTES + " bytes"));
            return;
        }
        try {
            request = Acp.JSON.readValue(body, Acp.RunCreateRequest.class);
        } catch (IOException e) {
            // the parser's message quotes the input; don't echo it back
            sendJson(ex, 400, new Acp.Error("invalid_input", "Malformed run request"));
            return;
        }
        if (request == null) {
            sendJson(ex, 400, new Acp.Error("invalid_input", "Malformed run request"));
            return;
        }
        AcpAgent agent = request.agentName() == null ? null : agents.get(request.agentName());
        if (agent == null) {
            sendJson(ex, 404, new Acp.Error("not_found", "Agent not found"));
            return;
        }
        if (request.mode() != null && request.mode() != Acp.RunMode.SYNC) {
            sendJson(ex, 400, new Acp.Error("invalid_input", "Only sync mode is supported"));
            return;
        }
        if (request.input() == null || request.input().isEmpty()) {
            sendJson(ex, 400, new Acp.Error("invalid_input", "Run input must contain at least one message"));
            return;
        }
        if (request.sessionId() != null && !VALID_SESSION_ID.matcher(request.sessionId()).matches()) {
            sendJson(ex, 400, new Acp.Error("invalid_input",
                    "session_id must be 1-128 characters of [A-Za-z0-9._:-]"));
            return;
        }
        if (!running.tryAcquire()) {
            sendJson(ex, 429, new Acp.Error("server_error", "Too many runs in progress; retry later"));
            return;
        }

        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        Acp.Run run;
        try {
            run = Acp.Run.completed(agent.manifest().name(), sessionId, agent.run(request.input(), sessionId),
                    createdAt);
        } catch (RuntimeException e) {
            // the exception may carry internals (paths, provider errors); the caller only gets a reference
            String reference = reference();
            log.warn("Agent {} failed on session {}, reference {}", agent.manifest().name(), sessionId, reference, e);
            run = Acp.Run.failed(agent.manifest().name(), sessionId,
                    new Acp.Error("server_error", "Internal error (reference " + reference + ")"), createdAt);
        } finally {
            running.release();
        }
        sendJson(ex, 200, run);
    }

    private interface Handler {
        void handle() throws IOException;
    }

    private static void handle(HttpExchange ex, String method, Handler handler) throws IOException {
        try (ex) {
            if (!method.equals(ex.getRequestMethod())) {
                sendJson(ex, 405, new Acp.Error("invalid_input", "Method not allowed"));
                return;
            }
            handler.handle();
        } catch (RuntimeException e) {
            String reference = reference();
            log.error("Unhandled error serving {}, reference {}", ex.getRequestURI().getPath(), reference, e);
            sendJson(ex, 500, new Acp.Error("server_error", "Internal error (reference " + reference + ")"));
        }
    }

    private static String reference() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Acp.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
