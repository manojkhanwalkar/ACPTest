package com.example.a2atest.agents;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agents.catalog.CatalogAgent;
import com.example.a2atest.agents.catalog.CatalogClientAgent;
import com.example.a2atest.agents.catalog.ProductCatalog;
import com.example.a2atest.llm.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogAgentTest {

    private A2aServer server;
    private A2aClient client;
    private CatalogClientAgent catalog;

    @BeforeEach
    void start() throws Exception {
        server = new A2aServer(0, new CatalogAgent(LlmConfig.placeholder(), ProductCatalog.sample()));
        client = new A2aClient(server.baseUri());
        catalog = new CatalogClientAgent(client);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void cardAdvertisesThreeSkillsAndJson() {
        A2a.AgentCard card = catalog.card();
        assertEquals(List.of("product-lookup", "product-search", "summarize"),
                card.skills().stream().map(A2a.AgentSkill::id).toList());
        assertTrue(card.defaultOutputModes().contains(A2a.APPLICATION_JSON));
    }

    @Test
    void lookupSendsAndReturnsJsonDataParts() {
        ProductCatalog.Product product = catalog.lookup("AUD-100");
        assertEquals(new ProductCatalog.Product("AUD-100", "Noise-cancelling headphones", "audio", 199.0, true), product);
    }

    @Test
    void searchExtractsAStructuredQueryFromText() {
        CatalogAgent.SearchResult result = catalog.search("audio products under $100");
        assertEquals(new ProductCatalog.SearchQuery("audio", 100.0, null), result.query());
        assertEquals(List.of("AUD-200"), result.products().stream().map(ProductCatalog.Product::sku).toList());
    }

    @Test
    void summarizes() {
        assertEquals("Summary (7 words): A2A connects agents.",
                catalog.summarize("A2A connects agents. It also streams results."));
    }

    @Test
    void routesBySkuInTheTextWhenNoSkillIsGiven() {
        A2a.Task task = catalog.ask("Is KEY-200 in stock?");
        assertEquals(A2a.TaskState.COMPLETED, task.status().state());
        assertEquals("KEY-200", task.status().message().data().get("sku").asText());
    }

    @Test
    void unknownSkillIsRejected() {
        A2a.Task task = client.sendMessage(catalog.card(), A2a.Message.user(List.of(A2a.Part.text("hi")), null, null,
                Map.of(A2a.SKILL_METADATA_KEY, "translate")));
        assertEquals(A2a.TaskState.REJECTED, task.status().state());
    }
}
