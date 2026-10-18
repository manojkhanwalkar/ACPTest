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

/**
 * Minimal ACP server on the JDK's built-in HTTP server. Implements the ACP REST endpoints needed for
 * discovery and synchronous runs:
 * <ul>
 *     <li>{@code GET  /ping}</li>
 *     <li>{@code GET  /agents} and {@code GET /agents/{name}}</li>
 *     <li>{@code POST /runs} with {@code "mode": "sync"}</li>
 * </ul>
 */
public final class AcpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpServer.class);

    private final Map<String, AcpAgent> agents = new LinkedHashMap<>();
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
            sendJson(ex, 404, new Acp.Error("not_found", "Agent not found: " + path));
        } else {
            sendJson(ex, 200, agent.manifest());
        }
    }

    private void createRun(HttpExchange ex) throws IOException {
        Instant createdAt = Instant.now();
        Acp.RunCreateRequest request;
        try {
            request = Acp.JSON.readValue(ex.getRequestBody(), Acp.RunCreateRequest.class);
        } catch (IOException e) {
            sendJson(ex, 400, new Acp.Error("invalid_input", "Malformed run request: " + e.getMessage()));
            return;
        }
        AcpAgent agent = agents.get(request.agentName());
        if (agent == null) {
            sendJson(ex, 404, new Acp.Error("not_found", "Agent not found: " + request.agentName()));
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

        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        Acp.Run run;
        try {
            run = Acp.Run.completed(agent.manifest().name(), sessionId, agent.run(request.input(), sessionId),
                    createdAt);
        } catch (RuntimeException e) {
            log.warn("Agent {} failed", request.agentName(), e);
            run = Acp.Run.failed(agent.manifest().name(), sessionId,
                    new Acp.Error("server_error", e.getMessage()), createdAt);
        }
        sendJson(ex, 200, run);
    }

    private interface Handler {
        void handle() throws IOException;
    }

    private static void handle(HttpExchange ex, String method, Handler handler) throws IOException {
        try (ex) {
            if (!method.equals(ex.getRequestMethod())) {
                sendJson(ex, 405, new Acp.Error("invalid_input", "Method not allowed: " + ex.getRequestMethod()));
                return;
            }
            handler.handle();
        } catch (RuntimeException e) {
            log.error("Unhandled error serving {}", ex.getRequestURI(), e);
            sendJson(ex, 500, new Acp.Error("server_error", e.getMessage()));
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Acp.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
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
