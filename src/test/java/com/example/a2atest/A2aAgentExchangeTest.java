package com.example.a2atest;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agent.Agent1;
import com.example.a2atest.agent.Agent2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aAgentExchangeTest {

    private A2aServer server;
    private A2aClient client;

    @BeforeEach
    void start() throws Exception {
        server = new A2aServer(0, new Agent2());
        client = new A2aClient(server.baseUri());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void agent2PublishesItsAgentCard() {
        A2a.AgentCard card = client.agentCard();
        assertEquals(Agent2.NAME, card.name());
        assertEquals(server.baseUri() + "/", card.url());
        assertEquals("JSONRPC", card.preferredTransport());
    }

    @Test
    void agent1ExchangesRequestAndResponseWithAgent2() {
        Agent1.Exchange exchange = new Agent1(client).askAgent2("What is the status of order #42?");

        assertEquals("What is the status of order #42?", exchange.request());
        assertEquals(A2a.TaskState.COMPLETED, exchange.task().status().state());
        assertEquals(A2a.Role.AGENT, exchange.task().status().message().role());
        assertEquals(2, exchange.task().history().size());
        assertEquals("Hello Agent1, Agent2 received your request: \"What is the status of order #42?\"",
                exchange.response());
    }

    @Test
    void messageSendUsesA2aJsonRpcWireFormat() throws Exception {
        String body = """
                {"jsonrpc": "2.0", "id": 1, "method": "message/send",
                 "params": {"message": {"kind": "message", "messageId": "m1", "role": "user",
                                        "parts": [{"kind": "text", "text": "ping"}]}}}
                """;
        String response = post(body);

        assertTrue(response.contains("\"jsonrpc\":\"2.0\""), response);
        assertTrue(response.contains("\"id\":1"), response);
        assertTrue(response.contains("\"kind\":\"task\""), response);
        assertTrue(response.contains("\"state\":\"completed\""), response);
        assertTrue(response.contains("\"role\":\"agent\""), response);
    }

    @Test
    void unknownMethodIsJsonRpcMethodNotFound() throws Exception {
        String response = post("{\"jsonrpc\": \"2.0\", \"id\": 7, \"method\": \"tasks/resubscribe\", \"params\": {}}");
        assertTrue(response.contains("\"code\":-32601"), response);
    }

    @Test
    void missingMessageIsInvalidParams() {
        A2a.AgentCard card = client.agentCard();
        A2aClient.A2aException e = assertThrows(A2aClient.A2aException.class,
                () -> client.sendMessage(card, new A2a.Message("message", "m1", A2a.Role.USER,
                        List.of(), null, null)));
        assertTrue(e.getMessage().contains("-32602"), e.getMessage());
    }

    private String post(String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.baseUri().resolve("/"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
