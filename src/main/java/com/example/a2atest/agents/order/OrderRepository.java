package com.example.a2atest.agents.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** In-memory order data the {@link OrderTools} read from. */
public final class OrderRepository {

    public enum Status { PROCESSING, SHIPPED, DELIVERED }

    public record Order(String id, String customer, String item, Status status, LocalDate eta) {
    }

    private final List<Order> orders;

    public OrderRepository(List<Order> orders) {
        this.orders = List.copyOf(orders);
    }

    /** A few sample orders. */
    public static OrderRepository sample() {
        return new OrderRepository(List.of(
                new Order("42", "alice", "noise-cancelling headphones", Status.SHIPPED, LocalDate.of(2026, 10, 27)),
                new Order("7", "alice", "USB-C cable", Status.DELIVERED, LocalDate.of(2026, 9, 30)),
                new Order("13", "bob", "mechanical keyboard", Status.PROCESSING, LocalDate.of(2026, 11, 3))));
    }

    public Optional<Order> find(String id) {
        return orders.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public List<Order> byCustomer(String customer) {
        return orders.stream().filter(o -> o.customer().equalsIgnoreCase(customer)).toList();
    }
}
