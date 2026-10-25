package com.example.a2atest.agents.order;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.stream.Collectors;

/** LangChain4j tools of the {@link OrderAgent}; the LLM decides which one to call. */
public final class OrderTools {

    public static final String ASK_FOR_INPUT = "askForInput";

    private final OrderRepository orders;

    public OrderTools(OrderRepository orders) {
        this.orders = orders;
    }

    @Tool("Looks up the status and delivery date of one order by its order number")
    public String getOrderStatus(@P("the order number, digits only") String orderId) {
        String id = orderId.replace("#", "").trim();
        return orders.find(id)
                .map(o -> "Order #" + o.id() + " (" + o.item() + ") is " + o.status().name().toLowerCase()
                        + (o.status() == OrderRepository.Status.DELIVERED ? ", delivered on " : ", expected on ")
                        + o.eta() + ".")
                .orElse("There is no order #" + id + ".");
    }

    @Tool("Lists all orders of a customer")
    public String listOrders(@P("the customer's name") String customer) {
        List<OrderRepository.Order> found = orders.byCustomer(customer.trim());
        if (found.isEmpty()) {
            return "No orders found for " + customer + ".";
        }
        return customer + " has " + found.size() + " orders: " + found.stream()
                .map(o -> "#" + o.id() + " " + o.item() + " (" + o.status().name().toLowerCase() + ")")
                .collect(Collectors.joining(", ")) + ".";
    }

    /**
     * Asks the requesting agent for missing information. {@link ReturnBehavior#IMMEDIATE} ends the AI service
     * call right after this tool, and the {@link OrderAgent} turns the question into an {@code input-required}
     * task state.
     */
    @Tool(name = ASK_FOR_INPUT, value = "Asks the requester a clarifying question when information needed to "
            + "answer, such as the order number, is missing", returnBehavior = ReturnBehavior.IMMEDIATE)
    public String askForInput(@P("the question to ask") String question) {
        return question;
    }
}
