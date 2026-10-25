package com.example.a2atest.agents;

import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agents.knowledge.KeywordContentRetriever;
import com.example.a2atest.agents.knowledge.KnowledgeAgent;
import com.example.a2atest.agents.knowledge.KnowledgeClientAgent;
import com.example.a2atest.llm.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeAgentTest {

    private A2aServer server;
    private KnowledgeClientAgent knowledge;

    @BeforeEach
    void start() throws Exception {
        server = new A2aServer(0, new KnowledgeAgent(LlmConfig.placeholder(),
                KeywordContentRetriever.loadClasspath("knowledge", KnowledgeAgent.DOCUMENTS)));
        knowledge = new KnowledgeClientAgent(new A2aClient(server.baseUri()));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void answersFromTheRetrievedDocumentAndCitesIt() {
        KnowledgeClientAgent.Answer answer = knowledge.ask("Can I return opened headphones?");
        assertEquals(List.of("returns-policy.md"), answer.sources());
        assertTrue(answer.text().contains("headphones"), answer.text());
    }

    @Test
    void shippingQuestionUsesTheShippingDocument() {
        KnowledgeClientAgent.Answer answer = knowledge.ask("How much does express shipping cost?");
        assertTrue(answer.sources().contains("shipping.md"), answer.sources().toString());
        assertTrue(answer.text().contains("$15"), answer.text());
    }

    @Test
    void saysItDoesNotKnowWhenNothingIsRetrieved() {
        KnowledgeClientAgent.Answer answer = knowledge.ask("Do you sell gift cards?");
        assertTrue(answer.text().startsWith("I don't know"), answer.text());
        assertTrue(answer.sources().isEmpty());
    }
}
