package com.example.acptest;

import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpAgent;
import com.example.acptest.acp.AcpServer;
import com.example.acptest.agent.Agent2;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the ACP server hardening (one test per fix). */
class AcpSecurityTest {

    private static HttpResponse<String> post(URI base, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(base.resolve("/runs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String run(String sessionId, String text) {
        return """
                {"agent_name": "agent2", "mode": "sync", %s
                 "input": [{"role": "user", "parts": [{"content_type": "text/plain", "content": "%s"}]}]}
                """.formatted(sessionId == null ? "" : "\"session_id\": \"" + sessionId + "\",", text);
    }

    @Test
    void oversizedRequestIsRefused() throws Exception {
        try (AcpServer server = new AcpServer(0, new Agent2())) {
            HttpResponse<String> response = post(server.baseUri(), run(null, "x".repeat(AcpServer.MAX_REQUEST_BYTES)));
            assertEquals(413, response.statusCode());
        }
    }

    @Test
    void malformedRequestDoesNotEchoInput() throws Exception {
        try (AcpServer server = new AcpServer(0, new Agent2())) {
            HttpResponse<String> response = post(server.baseUri(), "{\"agent_name\": <script>secret-token");
            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains("secret-token"), response.body());
            assertFalse(response.body().contains("<script>"), response.body());
        }
    }

    @Test
    void invalidSessionIdIsRejected() throws Exception {
        try (AcpServer server = new AcpServer(0, new Agent2())) {
            assertEquals(400, post(server.baseUri(), run("../../etc/passwd", "hi")).statusCode());
            assertEquals(400, post(server.baseUri(), run("a".repeat(129), "hi")).statusCode());
            assertEquals(200, post(server.baseUri(), run("session-1", "hi")).statusCode());
        }
    }

    @Test
    void agentFailureDoesNotLeakInternals() throws Exception {
        AcpAgent failing = new AcpAgent() {
            @Override
            public Acp.AgentManifest manifest() {
                return new Acp.AgentManifest("agent2", "fails", List.of(Acp.TEXT_PLAIN), List.of(Acp.TEXT_PLAIN));
            }

            @Override
            public List<Acp.Message> run(List<Acp.Message> input, String sessionId) {
                throw new IllegalStateException("db password=hunter2 at /srv/internal/config");
            }
        };
        try (AcpServer server = new AcpServer(0, failing)) {
            HttpResponse<String> response = post(server.baseUri(), run(null, "hi"));
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"failed\""), response.body());
            assertTrue(response.body().contains("Internal error (reference "), response.body());
            assertFalse(response.body().contains("hunter2"), response.body());
        }
    }

    @Test
    void agent2ChatMemoryIsBounded() {
        Agent2 agent2 = new Agent2();
        for (int i = 0; i < Agent2.MAX_SESSIONS + 10; i++) {
            agent2.run(List.of(Acp.Message.user("hi")), "session-" + i);
        }
        assertEquals(Agent2.MAX_SESSIONS, agent2.activeSessions());
    }
}
