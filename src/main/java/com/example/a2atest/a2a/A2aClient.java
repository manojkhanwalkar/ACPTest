package com.example.a2atest.a2a;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Minimal A2A client over {@link HttpClient}: agent card discovery, {@code message/send} (blocking or not),
 * {@code message/stream}, {@code tasks/get} and {@code tasks/cancel}. With an API key it sends
 * {@value A2a#API_KEY_HEADER} on every call.
 * <p>
 * The remote agent is not trusted: the client only posts to a card {@code url} on the same origin (scheme,
 * host and port) it fetched the card from, so a card can't redirect the API key or requests elsewhere, and it
 * reads at most {@value #MAX_RESPONSE_BYTES} bytes per response or stream.
 */
public final class A2aClient {

    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final URI baseUri;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public A2aClient(URI baseUri) {
        this(baseUri, null);
    }

    public A2aClient(URI baseUri, String apiKey) {
        this.baseUri = baseUri;
        this.apiKey = apiKey;
    }

    /** Fetches the agent card from the well-known location. */
    public A2a.AgentCard agentCard() {
        return send(HttpRequest.newBuilder(baseUri.resolve(A2a.AGENT_CARD_PATH)).GET(), A2a.AgentCard.class);
    }

    /** Sends a message to the agent whose card is given and waits until the task completes or needs input. */
    public A2a.Task sendMessage(A2a.AgentCard card, A2a.Message message) {
        return sendMessage(card, new A2a.MessageSendParams(message));
    }

    /** Sends a message with explicit options, e.g. {@link A2a.MessageSendConfiguration#nonBlocking()}. */
    public A2a.Task sendMessage(A2a.AgentCard card, A2a.MessageSendParams params) {
        return call(card, A2a.METHOD_MESSAGE_SEND, params, A2a.Task.class);
    }

    /** Fetches a task's current state; {@code historyLength} may be null for the full history. */
    public A2a.Task getTask(A2a.AgentCard card, String taskId, Integer historyLength) {
        return call(card, A2a.METHOD_TASKS_GET, new A2a.TaskQueryParams(taskId, historyLength), A2a.Task.class);
    }

    public A2a.Task cancelTask(A2a.AgentCard card, String taskId) {
        return call(card, A2a.METHOD_TASKS_CANCEL, new A2a.TaskIdParams(taskId), A2a.Task.class);
    }

    /**
     * Sends a message with {@code message/stream} and passes every server-sent event to {@code onEvent} as it
     * arrives. Returns when the server sends the final event (or closes the stream).
     */
    public void streamMessage(A2a.AgentCard card, A2a.Message message, Consumer<A2a.StreamEvent> onEvent) {
        A2a.JsonRpcRequest request = A2a.JsonRpcRequest.of(A2a.METHOD_MESSAGE_STREAM, new A2a.MessageSendParams(message));
        try {
            HttpRequest.Builder builder = post(card, A2a.JSON.writeValueAsBytes(request))
                    .header("Accept", "text/event-stream");
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                if (response.statusCode() / 100 != 2) {
                    throw new A2aException("HTTP " + response.statusCode() + " from " + baseUri);
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.startsWith("text/event-stream")) {
                    // errors such as "streaming not supported" come back as a plain JSON-RPC response
                    result(A2a.JSON.readValue(reader, A2a.JsonRpcResponse.class), A2a.Task.class);
                    return;
                }
                String line;
                int[] budget = {MAX_RESPONSE_BYTES};
                while ((line = readLine(reader, budget)) != null) {
                    if (line.startsWith("data:")) {
                        A2a.JsonRpcResponse event = A2a.JSON.readValue(line.substring(5).trim(), A2a.JsonRpcResponse.class);
                        onEvent.accept(result(event, A2a.StreamEvent.class));
                    }
                }
            }
        } catch (IOException e) {
            throw new A2aException("message/stream to " + baseUri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aException("Interrupted while streaming from " + baseUri, e);
        }
    }

    /** Reads one line, failing once the stream as a whole exceeds the remaining {@code budget} characters. */
    private static String readLine(BufferedReader reader, int[] budget) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (--budget[0] < 0) {
                throw new A2aException("Stream larger than " + MAX_RESPONSE_BYTES + " characters");
            }
            if (c == '\n') {
                return line.toString();
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.isEmpty() ? null : line.toString();
    }

    private <T> T call(A2a.AgentCard card, String method, Object params, Class<T> resultType) {
        A2a.JsonRpcRequest request = A2a.JsonRpcRequest.of(method, params);
        try {
            byte[] body = A2a.JSON.writeValueAsBytes(request);
            return result(send(post(card, body), A2a.JsonRpcResponse.class), resultType);
        } catch (IOException e) {
            throw new A2aException("Could not encode or decode " + method, e);
        }
    }

    private static <T> T result(A2a.JsonRpcResponse response, Class<T> type) throws IOException {
        if (response.error() != null) {
            throw new A2aException(response.error().code(),
                    "JSON-RPC error " + response.error().code() + ": " + response.error().message());
        }
        return A2a.JSON.treeToValue(response.result(), type);
    }

    private HttpRequest.Builder post(A2a.AgentCard card, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(card))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null) {
            builder.header(A2a.API_KEY_HEADER, apiKey);
        }
        return builder;
    }

    /** The card's JSON-RPC url, if it is on the same origin as {@code baseUri}. */
    private URI endpoint(A2a.AgentCard card) {
        URI url;
        try {
            url = URI.create(card.url());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new A2aException("Agent card has an invalid url: " + card.url());
        }
        boolean sameOrigin = baseUri.getScheme().equalsIgnoreCase(String.valueOf(url.getScheme()))
                && baseUri.getHost().equalsIgnoreCase(String.valueOf(url.getHost()))
                && port(baseUri) == port(url);
        if (!sameOrigin) {
            throw new A2aException("Agent card url " + card.url() + " is not on " + baseUri + "; refusing to call it");
        }
        return url;
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private <T> T send(HttpRequest.Builder builder, Class<T> type) {
        try {
            HttpResponse<InputStream> response = http.send(builder.timeout(Duration.ofMinutes(3)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new A2aException("Response from " + baseUri + " larger than " + MAX_RESPONSE_BYTES + " bytes");
            }
            if (response.statusCode() / 100 != 2) {
                throw new A2aException("HTTP " + response.statusCode() + " from " + baseUri);
            }
            return A2a.JSON.readValue(body, type);
        } catch (IOException e) {
            throw new A2aException("A2A request to " + baseUri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aException("Interrupted while calling " + baseUri, e);
        }
    }

    public static final class A2aException extends RuntimeException {

        private final Integer code;

        A2aException(String message) {
            this(null, message);
        }

        A2aException(Integer code, String message) {
            super(message);
            this.code = code;
        }

        A2aException(String message, Throwable cause) {
            super(message, cause);
            this.code = null;
        }

        /** The JSON-RPC error code, or null for transport errors. */
        public Integer code() {
            return code;
        }
    }
}
