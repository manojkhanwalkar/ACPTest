package com.example.a2atest;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aAgent;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.a2a.RequestContext;
import com.example.a2atest.a2a.TaskUpdater;
import com.example.a2atest.agent.Agent2;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol features of {@link A2aServer} that don't depend on a particular agent. */
class A2aServerFeaturesTest {

    /** Sleeps far longer than the server's timeout. */
    private static final class SlowAgent implements A2aAgent {

        @Override
        public A2a.AgentCard card(String url) {
            return A2a.AgentCard.of("slow", "Never finishes in time", url, false, List.of(A2a.TEXT_PLAIN), List.of());
        }

        @Override
        public void execute(RequestContext request, TaskUpdater task) {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.complete("too late");
        }
    }

    @Test
    void turnThatExceedsTheAgentTimeoutFails() throws Exception {
        try (A2aServer server = new A2aServer(0, new SlowAgent(),
                A2aServer.Options.defaults().withAgentTimeout(Duration.ofMillis(200)))) {
            A2aClient client = new A2aClient(server.baseUri());
            A2a.Task task = client.sendMessage(client.agentCard(), A2a.Message.user("hi", null));
            assertEquals(A2a.TaskState.FAILED, task.status().state());
            assertTrue(task.statusText().startsWith("Timed out"), task.statusText());
        }
    }

    @Test
    void oversizedRequestIsRefusedWith413() throws Exception {
        try (A2aServer server = new A2aServer(0, new Agent2(), A2aServer.Options.defaults().withMaxRequestBytes(100))) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/"))
                            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(500))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode());
        }
    }

    @Test
    void unknownTaskIsTaskNotFound() throws Exception {
        try (A2aServer server = new A2aServer(0, new Agent2())) {
            A2aClient client = new A2aClient(server.baseUri());
            A2aClient.A2aException e = assertThrows(A2aClient.A2aException.class,
                    () -> client.getTask(client.agentCard(), "no-such-task", null));
            assertEquals(A2a.JsonRpcError.TASK_NOT_FOUND, e.code());
        }
    }

    @Test
    void streamingIsRefusedWhenTheCardDoesNotAdvertiseIt() throws Exception {
        try (A2aServer server = new A2aServer(0, new Agent2())) {
            A2aClient client = new A2aClient(server.baseUri());
            A2aClient.A2aException e = assertThrows(A2aClient.A2aException.class,
                    () -> client.streamMessage(client.agentCard(), A2a.Message.user("hi", null), event -> {
                    }));
            assertEquals(A2a.JsonRpcError.UNSUPPORTED_OPERATION, e.code());
        }
    }

    @Test
    void completedTaskCannotBeContinuedOrCanceled() throws Exception {
        try (A2aServer server = new A2aServer(0, new Agent2())) {
            A2aClient client = new A2aClient(server.baseUri());
            A2a.AgentCard card = client.agentCard();
            A2a.Task done = client.sendMessage(card, A2a.Message.user("hi", null));

            A2aClient.A2aException continued = assertThrows(A2aClient.A2aException.class, () -> client.sendMessage(card,
                    A2a.Message.user(List.of(A2a.Part.text("more")), done.contextId(), done.id(), null)));
            assertEquals(A2a.JsonRpcError.INVALID_PARAMS, continued.code());

            A2aClient.A2aException canceled = assertThrows(A2aClient.A2aException.class,
                    () -> client.cancelTask(card, done.id()));
            assertEquals(A2a.JsonRpcError.TASK_NOT_CANCELABLE, canceled.code());
        }
    }

    @Test
    void metricsCountRequestsAndTaskStates() throws Exception {
        try (A2aServer server = new A2aServer(0, new Agent2())) {
            A2aClient client = new A2aClient(server.baseUri());
            client.sendMessage(client.agentCard(), A2a.Message.user("hi", null));
            String metrics = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(metrics.contains("\"method.message/send\":1"), metrics);
            assertTrue(metrics.contains("\"completed\":1"), metrics);
        }
    }
}
