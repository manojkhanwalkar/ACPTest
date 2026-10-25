package com.example.a2atest.agents.catalog;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aAgent;
import com.example.a2atest.a2a.RequestContext;
import com.example.a2atest.a2a.TaskUpdater;
import com.example.a2atest.agents.catalog.ProductCatalog.Product;
import com.example.a2atest.agents.catalog.ProductCatalog.SearchQuery;
import com.example.a2atest.llm.LlmConfig;
import com.example.a2atest.llm.LlmMetrics;
import com.example.a2atest.llm.RuleBasedChatModel;
import com.example.a2atest.llm.Rules;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Product catalog agent. Shows <b>several skills with routing</b> and <b>structured data</b>:
 * <ul>
 *     <li>three skills on its card: {@code product-lookup}, {@code product-search} and {@code summarize}</li>
 *     <li>a request is routed by the {@code skill} message metadata when the client sets it, otherwise by its
 *     content (a data part with {@code sku}, a SKU in the text, a "summarize" prefix, else search)</li>
 *     <li>input and output use A2A data parts ({@code application/json}) next to text: lookup takes
 *     {@code {"sku": ...}} and returns a {@link Product}; search lets LangChain4j turn free text into a
 *     {@link SearchQuery} record (structured output) and returns the matching products</li>
 * </ul>
 */
public final class CatalogAgent implements A2aAgent {

    public static final String NAME = "catalog-agent";
    public static final String SKILL_LOOKUP = "product-lookup";
    public static final String SKILL_SEARCH = "product-search";
    public static final String SKILL_SUMMARIZE = "summarize";

    interface QueryParser {

        @SystemMessage("Extract product search criteria from the request. Categories are audio, keyboards and "
                + "accessories. Leave fields null when the request doesn't mention them.")
        SearchQuery parse(@UserMessage String request);
    }

    interface Summarizer {

        @SystemMessage("Summarize the text in one short sentence.")
        String summarize(@UserMessage String text);
    }

    /** Search result sent as the data part of a {@code product-search} reply. */
    public record SearchResult(SearchQuery query, List<Product> products) {
    }

    private static final Pattern SKU = Pattern.compile("\\b([A-Z]{3}-\\d{3})\\b");

    private final ProductCatalog catalog;
    private final QueryParser parser;
    private final Summarizer summarizer;
    private final LlmMetrics metrics = new LlmMetrics(NAME);

    public CatalogAgent() {
        this(LlmConfig.fromEnv(), ProductCatalog.sample());
    }

    public CatalogAgent(LlmConfig llm, ProductCatalog catalog) {
        this.catalog = catalog;
        ChatModel model = llm.chatModel(placeholderModel(), List.of(metrics));
        parser = AiServices.builder(QueryParser.class).chatModel(model).build();
        summarizer = AiServices.builder(Summarizer.class).chatModel(model).build();
    }

    @Override
    public A2a.AgentCard card(String url) {
        List<String> both = List.of(A2a.TEXT_PLAIN, A2a.APPLICATION_JSON);
        return A2a.AgentCard.of(NAME, "Product lookup and search with JSON results, and text summaries", url,
                false, both, List.of(
                        new A2a.AgentSkill(SKILL_LOOKUP, "Product lookup", "Details of one product by SKU",
                                List.of("catalog", "json"), List.of("{\"sku\": \"AUD-100\"}"), both,
                                List.of(A2a.APPLICATION_JSON)),
                        new A2a.AgentSkill(SKILL_SEARCH, "Product search", "Products matching a free-text request",
                                List.of("catalog", "json"), List.of("audio products under $100"),
                                List.of(A2a.TEXT_PLAIN), both),
                        new A2a.AgentSkill(SKILL_SUMMARIZE, "Summarize", "One-sentence summary of a text",
                                List.of("text"), List.of("summarize: ..."), List.of(A2a.TEXT_PLAIN),
                                List.of(A2a.TEXT_PLAIN))));
    }

    @Override
    public void execute(RequestContext request, TaskUpdater task) {
        A2a.Message message = request.message();
        String skill = route(message);
        switch (skill) {
            case SKILL_LOOKUP -> lookup(message, task);
            case SKILL_SEARCH -> {
                SearchQuery query = parser.parse(message.text());
                List<Product> products = catalog.search(query);
                task.complete(List.of(A2a.Part.text(products.size() + " products match"),
                        A2a.Part.data(new SearchResult(query, products))));
            }
            case SKILL_SUMMARIZE -> task.complete(summarizer.summarize(
                    message.text().replaceFirst("(?i)^summari[sz]e:?\\s*", "")));
            default -> task.reject("Unknown skill '" + skill + "'; this agent offers " + SKILL_LOOKUP + ", "
                    + SKILL_SEARCH + " and " + SKILL_SUMMARIZE);
        }
    }

    /** The explicit {@code skill} metadata, else a choice based on the message content. */
    static String route(A2a.Message message) {
        String explicit = message.metadata(A2a.SKILL_METADATA_KEY);
        if (explicit != null) {
            return explicit;
        }
        JsonNode data = message.data();
        if (data != null && data.hasNonNull("sku")) {
            return SKILL_LOOKUP;
        }
        String text = message.text().trim();
        if (text.toLowerCase(Locale.ROOT).matches("^summari[sz]e\\b.*")) {
            return SKILL_SUMMARIZE;
        }
        return SKU.matcher(text).find() ? SKILL_LOOKUP : SKILL_SEARCH;
    }

    private void lookup(A2a.Message message, TaskUpdater task) {
        JsonNode data = message.data();
        String sku = data != null && data.hasNonNull("sku") ? data.get("sku").asText() : null;
        if (sku == null) {
            Matcher m = SKU.matcher(message.text());
            sku = m.find() ? m.group(1) : null;
        }
        if (sku == null) {
            task.reject("product-lookup needs a SKU, as {\"sku\": \"AUD-100\"} or in the text");
            return;
        }
        Optional<Product> product = catalog.find(sku);
        if (product.isEmpty()) {
            task.fail("No product with SKU " + sku);
            return;
        }
        Product p = product.get();
        task.complete(List.of(A2a.Part.text(p.name() + ": $" + p.price() + (p.inStock() ? ", in stock" : ", out of stock")),
                A2a.Part.data(p)));
    }

    @Override
    public Map<String, Object> metrics() {
        return Map.of("llm", metrics.snapshot());
    }

    private static final Pattern MAX_PRICE = Pattern.compile("(?i)(?:under|below|less than|max)\\s*\\$?(\\d+(?:\\.\\d+)?)");
    private static final List<String> CATEGORIES = List.of("audio", "keyboards", "accessories");

    /**
     * The placeholder LLM. For the query parser it returns the JSON of a {@link SearchQuery} found with regular
     * expressions (category word, "under $N", a quoted keyword); for the summarizer the first sentence.
     */
    public static RuleBasedChatModel placeholderModel() {
        return new RuleBasedChatModel(request -> {
            // structured output appends JSON-format instructions to the user message; parse only the request
            String text = Rules.lastUserText(request).split("\nYou must answer strictly")[0];
            if (Rules.systemText(request).startsWith("Extract product search criteria")) {
                String lower = text.toLowerCase(Locale.ROOT);
                String category = CATEGORIES.stream()
                        .filter(c -> lower.contains(c) || lower.contains(c.substring(0, c.length() - 1)))
                        .findFirst().orElse(null);
                Matcher price = MAX_PRICE.matcher(text);
                Matcher keyword = Pattern.compile("\"([^\"]+)\"").matcher(text);
                SearchQuery query = new SearchQuery(category, price.find() ? Double.valueOf(price.group(1)) : null,
                        keyword.find() ? keyword.group(1) : null);
                try {
                    return AiMessage.from(A2a.JSON.writeValueAsString(query));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            String first = text.split("(?<=[.!?])\\s+")[0].trim();
            int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
            return AiMessage.from("Summary (" + words + " words): " + first);
        });
    }
}
