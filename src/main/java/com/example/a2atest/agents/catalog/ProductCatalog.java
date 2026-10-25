package com.example.a2atest.agents.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** In-memory product catalog of the {@link CatalogAgent}. */
public final class ProductCatalog {

    /** A product; sent to clients as a JSON data part. */
    public record Product(String sku, String name, String category, double price, boolean inStock) {
    }

    /** Structured search criteria the LLM extracts from a free-text request; null fields don't filter. */
    public record SearchQuery(String category, Double maxPrice, String keyword) {
    }

    private final List<Product> products;

    public ProductCatalog(List<Product> products) {
        this.products = List.copyOf(products);
    }

    public static ProductCatalog sample() {
        return new ProductCatalog(List.of(
                new Product("AUD-100", "Noise-cancelling headphones", "audio", 199.0, true),
                new Product("AUD-200", "Wireless earbuds", "audio", 89.0, true),
                new Product("AUD-300", "Bookshelf speakers", "audio", 249.0, false),
                new Product("KEY-100", "Mechanical keyboard", "keyboards", 129.0, true),
                new Product("KEY-200", "Compact wireless keyboard", "keyboards", 59.0, true),
                new Product("ACC-100", "USB-C cable", "accessories", 12.0, true)));
    }

    public Optional<Product> find(String sku) {
        return products.stream().filter(p -> p.sku().equalsIgnoreCase(sku.trim())).findFirst();
    }

    public List<Product> search(SearchQuery query) {
        return products.stream()
                .filter(p -> blank(query.category()) || p.category().equalsIgnoreCase(query.category()))
                .filter(p -> query.maxPrice() == null || p.price() <= query.maxPrice())
                .filter(p -> blank(query.keyword())
                        || p.name().toLowerCase(Locale.ROOT).contains(query.keyword().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
