package com.example.a2atest.agents;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agents.order.OrderAgent;
import com.example.a2atest.agents.order.OrderClientAgent;
import com.example.a2atest.agents.order.OrderRepository;
import com.example.a2atest.llm.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderAgentTest {

    private A2aServer server;
    private A2aClient client;

    @BeforeEach
    void start() throws Exception {
        server = new A2aServer(0, new OrderAgent(LlmConfig.placeholder(), OrderRepository.sample()));
        client = new A2aClient(server.baseUri());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void looksUpAnOrderWithItsTool() {
        OrderClientAgent.Answer answer = new OrderClientAgent(client).ask("What is the status of order #42?");
        assertEquals("Order #42 (noise-cancelling headphones) is shipped, expected on 2026-10-27.", answer.text());
        assertTrue(answer.questions().isEmpty());
    }

    @Test
    void listsTheOrdersOfACustomer() {
        String text = new OrderClientAgent(client).ask("List the orders of alice").text();
        assertTrue(text.startsWith("alice has 2 orders"), text);
    }

    @Test
    void missingOrderNumberPutsTheTaskInInputRequiredAndTheSameTaskContinues() {
        A2a.AgentCard card = client.agentCard();
        A2a.Task first = client.sendMessage(card, A2a.Message.user("Where is my order?", "ctx-1"));
        assertEquals(A2a.TaskState.INPUT_REQUIRED, first.status().state());
        assertEquals("Which order do you mean? Please send the order number.", first.statusText());

        A2a.Task second = client.sendMessage(card,
                A2a.Message.user(List.of(A2a.Part.text("Order 13 please")), "ctx-1", first.id(), null));
        assertEquals(first.id(), second.id());
        assertEquals(A2a.TaskState.COMPLETED, second.status().state());
        assertTrue(second.statusText().startsWith("Order #13 (mechanical keyboard) is processing"), second.statusText());
        assertEquals(4, second.history().size()); // question, clarification request, answer, reply
    }

    @Test
    void clientAgentAnswersTheClarifyingQuestion() {
        OrderClientAgent.Answer answer = new OrderClientAgent(client).ask("Where is my order?", q -> "It's #7");
        assertEquals(List.of("Which order do you mean? Please send the order number."), answer.questions());
        assertTrue(answer.text().startsWith("Order #7 (USB-C cable) is delivered"), answer.text());
    }

    @Test
    void clientAgentWithoutAnAnswerCancelsTheTask() {
        OrderClientAgent.Answer answer = new OrderClientAgent(client).ask("Where is my order?");
        assertTrue(answer.needsInput());
        A2a.Task task = client.getTask(client.agentCard(), answer.taskId(), null);
        assertEquals(A2a.TaskState.CANCELED, task.status().state());
        assertFalse(answer.text().isBlank());
    }
}
