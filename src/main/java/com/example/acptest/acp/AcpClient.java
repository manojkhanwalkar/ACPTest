package com.example.acptest.acp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Minimal ACP client over {@link HttpClient}: agent discovery and synchronous runs. */
public final class AcpClient {

    /** Largest response body read from a server; a hostile server can't make the client buffer more. */
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final URI baseUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AcpClient(URI baseUri) {
        this.baseUri = baseUri;
    }

    public List<Acp.AgentManifest> agents() {
        return send(HttpRequest.newBuilder(baseUri.resolve("/agents")).GET(), Acp.AgentsListResponse.class).agents();
    }

    public Acp.AgentManifest agent(String name) {
        return send(HttpRequest.newBuilder(baseUri.resolve("/agents/" + name)).GET(), Acp.AgentManifest.class);
    }

    /** Creates a run in sync mode and returns it once the agent has finished. */
    public Acp.Run runSync(String agentName, String sessionId, List<Acp.Message> input) {
        Acp.RunCreateRequest request = new Acp.RunCreateRequest(agentName, sessionId, input, Acp.RunMode.SYNC);
        try {
            byte[] body = Acp.JSON.writeValueAsBytes(request);
            return send(HttpRequest.newBuilder(baseUri.resolve("/runs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)), Acp.Run.class);
        } catch (IOException e) {
            throw new AcpException("Could not serialize run request", e);
        }
    }

    private <T> T send(HttpRequest.Builder builder, Class<T> type) {
        try {
            HttpResponse<InputStream> response = http.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new AcpException("ACP response from " + baseUri + " is larger than " + MAX_RESPONSE_BYTES + " bytes");
            }
            if (response.statusCode() / 100 != 2) {
                Acp.Error error = Acp.JSON.readValue(body, Acp.Error.class);
                throw new AcpException("ACP " + response.statusCode() + " " + error.code() + ": " + error.message());
            }
            return Acp.JSON.readValue(body, type);
        } catch (IOException e) {
            throw new AcpException("ACP request to " + baseUri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcpException("Interrupted while calling " + baseUri, e);
        }
    }

    public static final class AcpException extends RuntimeException {
        AcpException(String message) {
            super(message);
        }

        AcpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
