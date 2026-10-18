package com.example.acptest;

import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpClient;
import com.example.acptest.acp.AcpServer;
import com.example.acptest.agent.Agent1;
import com.example.acptest.agent.Agent2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExchangeTest {

    private AcpServer server;
    private AcpClient client;

    @BeforeEach
    void start() throws Exception {
        server = new AcpServer(0, new Agent2());
        client = new AcpClient(server.baseUri());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void agent2IsDiscoverable() {
        List<Acp.AgentManifest> agents = client.agents();
        assertEquals(1, agents.size());
        assertEquals(Agent2.NAME, agents.getFirst().name());
    }

    @Test
    void agent1ExchangesRequestAndResponseWithAgent2() {
        Agent1.Exchange exchange = new Agent1(client).askAgent2("What is the status of order #42?");

        assertEquals("What is the status of order #42?", exchange.request());
        assertEquals(Acp.RunStatus.COMPLETED, exchange.run().status());
        assertEquals("agent/" + Agent2.NAME, exchange.run().output().getFirst().role());
        assertEquals("Hello Agent1, Agent2 received your request: \"What is the status of order #42?\"",
                exchange.response());
    }

    @Test
    void runRequestUsesAcpWireFormat() throws Exception {
        String body = """
                {"agent_name": "agent2", "mode": "sync",
                 "input": [{"role": "user", "parts": [{"content_type": "text/plain", "content": "ping"}]}]}
                """;
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.baseUri().resolve("/runs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"completed\""), response.body());
        assertTrue(response.body().contains("\"role\":\"agent/agent2\""), response.body());
        assertTrue(response.body().contains("\"content_type\":\"text/plain\""), response.body());
    }

    @Test
    void unknownAgentIsNotFound() {
        AcpClient.AcpException e = assertThrows(AcpClient.AcpException.class,
                () -> client.runSync("nobody", null, List.of(Acp.Message.user("hi"))));
        assertTrue(e.getMessage().contains("not_found"), e.getMessage());
    }
}
