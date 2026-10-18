package com.example.a2atest.a2a;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal A2A client over {@link HttpClient}: agent card discovery and blocking {@code message/send}. */
public final class A2aClient {

    private final URI baseUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public A2aClient(URI baseUri) {
        this.baseUri = baseUri;
    }

    /** Fetches the agent card from the well-known location. */
    public A2a.AgentCard agentCard() {
        return send(HttpRequest.newBuilder(baseUri.resolve(A2a.AGENT_CARD_PATH)).GET(), A2a.AgentCard.class);
    }

    /** Sends a message to the agent whose card is given and returns the resulting task. */
    public A2a.Task sendMessage(A2a.AgentCard card, A2a.Message message) {
        A2a.JsonRpcRequest request = A2a.JsonRpcRequest.of(A2a.METHOD_MESSAGE_SEND, new A2a.MessageSendParams(message));
        try {
            byte[] body = A2a.JSON.writeValueAsBytes(request);
            A2a.JsonRpcResponse response = send(HttpRequest.newBuilder(URI.create(card.url()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)), A2a.JsonRpcResponse.class);
            if (response.error() != null) {
                throw new A2aException("JSON-RPC error " + response.error().code() + ": " + response.error().message());
            }
            return A2a.JSON.treeToValue(response.result(), A2a.Task.class);
        } catch (IOException e) {
            throw new A2aException("Could not encode or decode message/send", e);
        }
    }

    private <T> T send(HttpRequest.Builder builder, Class<T> type) {
        try {
            HttpResponse<byte[]> response = http.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new A2aException("HTTP " + response.statusCode() + " from " + baseUri);
            }
            return A2a.JSON.readValue(response.body(), type);
        } catch (IOException e) {
            throw new A2aException("A2A request to " + baseUri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aException("Interrupted while calling " + baseUri, e);
        }
    }

    public static final class A2aException extends RuntimeException {
        A2aException(String message) {
            super(message);
        }

        A2aException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
