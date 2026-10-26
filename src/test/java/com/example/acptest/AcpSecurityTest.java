package com.example.acptest;

import com.example.a2atest.memory.ConversationMemory;
import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpAgent;
import com.example.acptest.acp.AcpServer;
import com.example.acptest.agent.Agent2;
import com.example.acptest.agent.PlaceholderChatModel;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void largeUploadGetsTheRefusalNotAReset() throws Exception {
        try (AcpServer server = new AcpServer(0, new Agent2())) {
            HttpResponse<String> response = post(server.baseUri(), run(null, "x".repeat(20 * 1024 * 1024)));
            assertEquals(413, response.statusCode());
        }
    }

    private static final String SLOW = "slow";

    private static ConversationMemory memory(InMemoryChatMemoryStore store, int max) {
        return new ConversationMemory(20, max, Duration.ofMinutes(30), store, false, Clock.systemUTC());
    }

    @Test
    void agent2EvictsTheChatMemoryItself() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        Agent2 agent2 = new Agent2(new PlaceholderChatModel(r -> "ok"), memory(store, 3));
        for (int i = 0; i < 5; i++) {
            agent2.run(List.of(Acp.Message.user("hi")), "session-" + i);
        }
        assertEquals(3, agent2.activeSessions());
        assertTrue(store.getMessages("session-0").isEmpty(), "evicted conversation still stored");
        assertFalse(store.getMessages("session-4").isEmpty());
    }

    @Test
    void sessionInFlightIsNotEvictedOrOrphaned() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        Agent2 agent2 = new Agent2(new PlaceholderChatModel(request -> {
            if (request.equals(SLOW)) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "ok";
        }), memory(store, 3));

        Thread slow = Thread.ofVirtual().start(() -> agent2.run(List.of(Acp.Message.user(SLOW)), "slow-session"));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
            agent2.run(List.of(Acp.Message.user("hi")), "session-" + i);
        }
        release.countDown();
        slow.join();

        // the slow session kept its place and its memory, and the limit still holds
        assertEquals(3, agent2.activeSessions());
        assertFalse(store.getMessages("slow-session").isEmpty());
        agent2.run(List.of(Acp.Message.user("hi")), "session-9");
        agent2.run(List.of(Acp.Message.user("hi")), "session-10");
        agent2.run(List.of(Acp.Message.user("hi")), "session-11");
        assertTrue(store.getMessages("slow-session").isEmpty(), "slow session never evicted after its call");
    }
}
