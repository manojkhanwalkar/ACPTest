package com.example.a2atest.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Minimal A2A server on the JDK's built-in HTTP server, hosting one agent:
 * <ul>
 *     <li>{@code GET  /.well-known/agent-card.json} returns the agent card</li>
 *     <li>{@code POST /} is the JSON-RPC 2.0 endpoint; only {@code message/send} is implemented</li>
 * </ul>
 * Each {@code message/send} is answered with a completed {@link A2a.Task}.
 */
public final class A2aServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(A2aServer.class);

    private final A2aAgent agent;
    private final HttpServer http;

    /** Starts a server on localhost; pass port 0 to pick a free port. */
    public A2aServer(int port, A2aAgent agent) throws IOException {
        this.agent = agent;
        http = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        http.createContext(A2a.AGENT_CARD_PATH, this::agentCard);
        http.createContext("/", this::jsonRpc);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        log.info("A2A server listening on {} hosting {}", baseUri(), card().name());
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + http.getAddress().getPort());
    }

    private A2a.AgentCard card() {
        return agent.card(baseUri() + "/");
    }

    private void agentCard(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            sendJson(ex, 200, card());
        }
    }

    private void jsonRpc(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod()) || !"/".equals(ex.getRequestURI().getPath())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            A2a.JsonRpcRequest request;
            try {
                request = A2a.JSON.readValue(ex.getRequestBody(), A2a.JsonRpcRequest.class);
            } catch (IOException e) {
                sendError(ex, null, A2a.JsonRpcError.PARSE_ERROR, "Parse error: " + e.getMessage());
                return;
            }
            if (!"2.0".equals(request.jsonrpc()) || request.method() == null) {
                sendError(ex, request.id(), A2a.JsonRpcError.INVALID_REQUEST, "Not a JSON-RPC 2.0 request");
                return;
            }
            if (!A2a.METHOD_MESSAGE_SEND.equals(request.method())) {
                sendError(ex, request.id(), A2a.JsonRpcError.METHOD_NOT_FOUND, "Method not found: " + request.method());
                return;
            }
            sendMessage(ex, request);
        }
    }

    private void sendMessage(HttpExchange ex, A2a.JsonRpcRequest request) throws IOException {
        A2a.Message message;
        try {
            message = A2a.JSON.treeToValue(request.params(), A2a.MessageSendParams.class).message();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            message = null;
        }
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            sendError(ex, request.id(), A2a.JsonRpcError.INVALID_PARAMS, "params.message with parts is required");
            return;
        }

        String contextId = message.contextId() != null ? message.contextId() : UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        A2a.Task task;
        try {
            String reply = agent.reply(message, contextId);
            task = A2a.Task.of(taskId, contextId, A2a.TaskState.COMPLETED, message,
                    A2a.Message.agent(reply, contextId, taskId));
        } catch (RuntimeException e) {
            log.warn("Agent {} failed", card().name(), e);
            task = A2a.Task.of(taskId, contextId, A2a.TaskState.FAILED, message,
                    A2a.Message.agent("Error: " + e.getMessage(), contextId, taskId));
        }
        sendJson(ex, 200, A2a.JsonRpcResponse.success(request.id(), task));
    }

    private static void sendError(HttpExchange ex, Object id, int code, String message) throws IOException {
        // JSON-RPC errors are still delivered with HTTP 200
        sendJson(ex, 200, A2a.JsonRpcResponse.failure(id, new A2a.JsonRpcError(code, message)));
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = A2a.JSON.writeValueAsBytes(body);
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
