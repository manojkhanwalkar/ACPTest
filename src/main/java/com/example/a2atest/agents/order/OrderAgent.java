package com.example.a2atest.agents.order;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aAgent;
import com.example.a2atest.a2a.RequestContext;
import com.example.a2atest.a2a.TaskUpdater;
import com.example.a2atest.llm.LlmConfig;
import com.example.a2atest.llm.LlmMetrics;
import com.example.a2atest.llm.RuleBasedChatModel;
import com.example.a2atest.llm.Rules;
import com.example.a2atest.memory.ConversationMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.service.tool.ToolExecution;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Order-status agent. Shows <b>tools</b>, a <b>system prompt</b> and the A2A <b>input-required</b> state:
 * <ul>
 *     <li>the LLM answers with {@link OrderTools} ({@code getOrderStatus}, {@code listOrders}) over an
 *     {@link OrderRepository}</li>
 *     <li>when the order number is missing it calls {@code askForInput}; the task then ends the turn in
 *     {@code input-required} with the question, and the client continues the same task (same {@code taskId})
 *     with the answer</li>
 *     <li>conversation memory per {@code contextId}, bounded by a {@link ConversationMemory}</li>
 * </ul>
 */
public final class OrderAgent implements A2aAgent {

    public static final String NAME = "order-agent";

    interface OrderAssistant extends ChatMemoryAccess {

        @SystemMessage("""
                You are the order desk of an online shop, answering other agents.
                Use getOrderStatus or listOrders to look orders up; never invent order data.
                If the request doesn't say which order or customer it is about, call askForInput with a short question.
                Answer in one or two sentences.""")
        Result<String> chat(@MemoryId String contextId, @UserMessage String message);
    }

    private final OrderAssistant assistant;
    private final ConversationMemory memory = ConversationMemory.inMemory(20, 1000, Duration.ofMinutes(30));
    private final LlmMetrics metrics = new LlmMetrics(NAME);

    /** Uses the LLM configured in the environment (the placeholder rule by default). */
    public OrderAgent() {
        this(LlmConfig.fromEnv(), OrderRepository.sample());
    }

    public OrderAgent(LlmConfig llm, OrderRepository orders) {
        assistant = AiServices.builder(OrderAssistant.class)
                .chatModel(llm.chatModel(placeholderModel(), List.of(metrics)))
                .chatMemoryProvider(memory)
                .tools(new OrderTools(orders))
                .build();
        memory.bind(assistant);
    }

    @Override
    public A2a.AgentCard card(String url) {
        return A2a.AgentCard.of(NAME, "Looks up order status and order lists; asks for the order number if missing",
                url, false, List.of(A2a.TEXT_PLAIN), List.of(
                        new A2a.AgentSkill("order-status", "Order status", "Status and delivery date of an order",
                                List.of("orders", "tools"), List.of("What is the status of order #42?"), null, null),
                        new A2a.AgentSkill("order-list", "Customer orders", "All orders of a customer",
                                List.of("orders", "tools"), List.of("List the orders of alice"), null, null)));
    }

    @Override
    public void execute(RequestContext request, TaskUpdater task) {
        memory.touch(request.memoryKey());
        Result<String> result = assistant.chat(request.memoryKey(), request.message().text());
        Optional<ToolExecution> question = result.toolExecutions().stream()
                .filter(t -> OrderTools.ASK_FOR_INPUT.equals(t.request().name()))
                .findFirst();
        if (question.isPresent()) {
            task.requireInput(question.get().result());
        } else {
            task.complete(result.content());
        }
    }

    @Override
    public Map<String, Object> metrics() {
        return Map.of("llm", metrics.snapshot(), "memory", memory.snapshot());
    }

    private static final Pattern ORDER_NUMBER = Pattern.compile("#?\\b(\\d+)\\b");
    private static final Pattern CUSTOMER = Pattern.compile("(?i)orders? (?:of|for|by) (\\w+)");

    /**
     * The placeholder LLM: an order number in the request means {@code getOrderStatus}, "orders of NAME" means
     * {@code listOrders}, anything else means {@code askForInput}; tool results are returned as the answer.
     */
    public static RuleBasedChatModel placeholderModel() {
        return new RuleBasedChatModel(request -> {
            List<ToolExecutionResultMessage> results = Rules.toolResultsSinceLastUser(request);
            if (!results.isEmpty()) {
                return AiMessage.from(results.stream().map(ToolExecutionResultMessage::text)
                        .collect(Collectors.joining(" ")));
            }
            String text = Rules.lastUserText(request);
            Matcher customer = CUSTOMER.matcher(text);
            if (customer.find()) {
                return Rules.callTool("listOrders", Map.of("customer", customer.group(1)));
            }
            Matcher order = ORDER_NUMBER.matcher(text);
            if (order.find()) {
                return Rules.callTool("getOrderStatus", Map.of("orderId", order.group(1)));
            }
            return Rules.callTool(OrderTools.ASK_FOR_INPUT,
                    Map.of("question", "Which order do you mean? Please send the order number."));
        });
    }
}
