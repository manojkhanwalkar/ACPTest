package com.example.acptest.acp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Minimal ACP client over {@link HttpClient}: agent discovery and synchronous runs. */
public final class AcpClient {

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
            HttpResponse<byte[]> response = http.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                Acp.Error error = Acp.JSON.readValue(response.body(), Acp.Error.class);
                throw new AcpException("ACP " + response.statusCode() + " " + error.code() + ": " + error.message());
            }
            return Acp.JSON.readValue(response.body(), type);
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
