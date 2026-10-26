package com.example.acptest.acp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal ACP client over {@link HttpClient}: agent discovery and synchronous runs. */
public final class AcpClient {

    /** Largest response body read from a server; a hostile server can't make the client buffer more. */
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    // closes response streams whose body isn't fully read within the request timeout
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "acp-client-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    private final URI baseUri;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AcpClient(URI baseUri) {
        this(baseUri, Duration.ofSeconds(30));
    }

    /** {@code timeout} covers the whole exchange, including reading the response body. */
    AcpClient(URI baseUri, Duration timeout) {
        this.baseUri = baseUri;
        this.timeout = timeout;
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
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // the request timeout stops at the response headers, so the body read is bounded separately
            HttpResponse<InputStream> response = http.send(builder.timeout(timeout).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = response.body()) {
                AtomicBoolean timedOut = new AtomicBoolean();
                ScheduledFuture<?> guard = TIMEOUTS.schedule(() -> {
                    timedOut.set(true);
                    closeQuietly(in);
                }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                try {
                    body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                } catch (IOException e) {
                    if (timedOut.get()) {
                        throw new AcpException("ACP response from " + baseUri + " timed out after " + timeout, e);
                    }
                    throw e;
                } finally {
                    guard.cancel(false);
                }
                if (timedOut.get()) {
                    throw new AcpException("ACP response from " + baseUri + " timed out after " + timeout);
                }
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new AcpException("ACP response from " + baseUri + " is larger than " + MAX_RESPONSE_BYTES + " bytes");
            }
            if (response.statusCode() / 100 != 2) {
                Acp.Error error = Acp.JSON.readValue(body, Acp.Error.class);
                throw new AcpException(response.statusCode(),
                        "ACP " + response.statusCode() + " " + error.code() + ": " + error.message());
            }
            return Acp.JSON.readValue(body, type);
        } catch (IOException e) {
            throw new AcpException("ACP request to " + baseUri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcpException("Interrupted while calling " + baseUri, e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // the reader sees the closed stream
        }
    }

    public static final class AcpException extends RuntimeException {
        private final int statusCode;

        AcpException(String message) {
            this(-1, message);
        }

        AcpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        AcpException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        /** HTTP status of an error response, or -1 if the request failed before one arrived. */
        public int statusCode() {
            return statusCode;
        }

        /** True for overload (HTTP 429): the request was refused and can be retried later. */
        public boolean isRetryable() {
            return statusCode == 429;
        }
    }
}
