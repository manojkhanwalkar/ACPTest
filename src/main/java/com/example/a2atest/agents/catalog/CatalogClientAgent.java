package com.example.a2atest.agents.catalog;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.agents.RemoteAgent;
import com.example.a2atest.agents.catalog.ProductCatalog.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Client of the {@link CatalogAgent}. Picks the skill explicitly with the {@code skill} metadata, sends JSON
 * data parts and decodes the JSON data part of the reply into Java records.
 */
public final class CatalogClientAgent extends RemoteAgent {

    public CatalogClientAgent(A2aClient a2a) {
        super(a2a);
    }

    /** {@code product-lookup} with a {@code {"sku": ...}} data part; returns the product from the reply's data part. */
    public Product lookup(String sku) {
        A2a.Task task = call(CatalogAgent.SKILL_LOOKUP, List.of(A2a.Part.data(Map.of("sku", sku))));
        return data(task, Product.class);
    }

    /** {@code product-search} with free text; the agent extracts the criteria and returns them with the matches. */
    public CatalogAgent.SearchResult search(String request) {
        A2a.Task task = call(CatalogAgent.SKILL_SEARCH, List.of(A2a.Part.text(request)));
        return data(task, CatalogAgent.SearchResult.class);
    }

    public String summarize(String text) {
        return call(CatalogAgent.SKILL_SUMMARIZE, List.of(A2a.Part.text(text))).statusText();
    }

    /** Sends text without naming a skill, so the agent routes it by content; returns the whole task. */
    public A2a.Task ask(String text) {
        return send(text);
    }

    private A2a.Task call(String skill, List<A2a.Part> parts) {
        A2a.Task task = a2a.sendMessage(card(),
                A2a.Message.user(parts, contextId, null, Map.of(A2a.SKILL_METADATA_KEY, skill)));
        if (task.status().state() != A2a.TaskState.COMPLETED) {
            throw new IllegalStateException(skill + " ended " + task.status().state().wire() + ": " + task.statusText());
        }
        return task;
    }

    private static <T> T data(A2a.Task task, Class<T> type) {
        JsonNode data = task.status().message().data();
        if (data == null) {
            throw new IllegalStateException("Reply of task " + task.id() + " has no data part");
        }
        try {
            return A2a.JSON.treeToValue(data, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected data in task " + task.id(), e);
        }
    }
}
