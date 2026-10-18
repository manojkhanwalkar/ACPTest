package com.example.a2atest;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agent.Agent1;
import com.example.a2atest.agent.Agent2;

/**
 * Starts an A2A server hosting Agent2, then has Agent1 fetch Agent2's agent card and exchange one
 * request/response with it over A2A.
 */
public final class A2aTestApp {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        try (A2aServer server = new A2aServer(port, new Agent2())) {
            A2aClient client = new A2aClient(server.baseUri());
            A2a.AgentCard card = client.agentCard();
            System.out.println("Agent card at " + server.baseUri() + A2a.AGENT_CARD_PATH + ": "
                    + card.name() + " (" + card.description() + ") -> " + card.url());

            Agent1 agent1 = new Agent1(client);
            Agent1.Exchange exchange = agent1.askAgent2("Hi Agent2, what is the status of order #42?");

            System.out.println("Agent1 -> Agent2 : " + exchange.request());
            System.out.println("Agent2 -> Agent1 : " + exchange.response());
            System.out.println("Task " + exchange.task().id() + " state=" + exchange.task().status().state().wire());
        }
    }
}
