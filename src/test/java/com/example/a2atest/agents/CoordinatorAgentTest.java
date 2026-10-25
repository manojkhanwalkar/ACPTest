package com.example.a2atest.agents;

import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agents.coordinator.CoordinatorAgent;
import com.example.a2atest.agents.coordinator.CoordinatorClientAgent;
import com.example.a2atest.agents.coordinator.DelegationTools;
import com.example.a2atest.agents.knowledge.KeywordContentRetriever;
import com.example.a2atest.agents.knowledge.KnowledgeAgent;
import com.example.a2atest.agents.knowledge.KnowledgeClientAgent;
import com.example.a2atest.agents.order.OrderAgent;
import com.example.a2atest.agents.order.OrderClientAgent;
import com.example.a2atest.agents.order.OrderRepository;
import com.example.a2atest.llm.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorAgentTest {

    private A2aServer orders;
    private A2aServer knowledge;
    private A2aServer coordinator;
    private CoordinatorClientAgent client;

    @BeforeEach
    void start() throws Exception {
        LlmConfig llm = LlmConfig.placeholder();
        orders = new A2aServer(0, new OrderAgent(llm, OrderRepository.sample()));
        knowledge = new A2aServer(0, new KnowledgeAgent(llm,
                KeywordContentRetriever.loadClasspath("knowledge", KnowledgeAgent.DOCUMENTS)));
        coordinator = new A2aServer(0, new CoordinatorAgent(llm, new DelegationTools(
                new OrderClientAgent(new A2aClient(orders.baseUri())),
                new KnowledgeClientAgent(new A2aClient(knowledge.baseUri())))));
        client = new CoordinatorClientAgent(new A2aClient(coordinator.baseUri()));
    }

    @AfterEach
    void stop() {
        coordinator.close();
        knowledge.close();
        orders.close();
    }

    @Test
    void fansOutToBothAgentsAndCombinesTheAnswers() {
        CoordinatorClientAgent.Answer answer = client.ask("Where is order #42 and what is the return policy?");
        assertEquals(List.of("order-agent", "knowledge-agent"),
                answer.delegations().stream().map(CoordinatorAgent.Delegation::agent).toList());
        assertTrue(answer.text().contains("Order: Order #42"), answer.text());
        assertTrue(answer.text().contains("Policy: According to our policy"), answer.text());
        assertTrue(answer.text().contains("returns-policy.md"), answer.text());
    }

    @Test
    void delegatesOnlyWhereNeeded() {
        CoordinatorClientAgent.Answer answer = client.ask("How long does shipping take?");
        assertEquals(List.of("knowledge-agent"),
                answer.delegations().stream().map(CoordinatorAgent.Delegation::agent).toList());
    }

    @Test
    void passesOnTheOrderAgentsClarifyingQuestion() {
        CoordinatorClientAgent.Answer answer = client.ask("Where is my order?");
        assertTrue(answer.text().contains("needs more information"), answer.text());
    }
}
