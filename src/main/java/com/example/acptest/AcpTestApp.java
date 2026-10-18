package com.example.acptest;

import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpClient;
import com.example.acptest.acp.AcpServer;
import com.example.acptest.agent.Agent1;
import com.example.acptest.agent.Agent2;

/**
 * Starts an ACP server hosting Agent2, then has Agent1 discover Agent2 and exchange one
 * request/response with it over ACP.
 */
public final class AcpTestApp {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8000;
        try (AcpServer server = new AcpServer(port, new Agent2())) {
            AcpClient client = new AcpClient(server.baseUri());
            System.out.println("Agents available at " + server.baseUri() + ": "
                    + client.agents().stream().map(Acp.AgentManifest::name).toList());

            Agent1 agent1 = new Agent1(client);
            Agent1.Exchange exchange = agent1.askAgent2("Hi Agent2, what is the status of order #42?");

            System.out.println("Agent1 -> Agent2 : " + exchange.request());
            System.out.println("Agent2 -> Agent1 : " + exchange.response());
            System.out.println("Run " + exchange.run().runId() + " status=" + exchange.run().status().wire());
        }
    }
}
