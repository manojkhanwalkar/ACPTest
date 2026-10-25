package com.example.a2atest.agents.coordinator;

import com.example.a2atest.agents.knowledge.KnowledgeClientAgent;
import com.example.a2atest.agents.order.OrderClientAgent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * LangChain4j tools of the {@link CoordinatorAgent}. Each tool is a call to another A2A agent through its
 * client agent, so the coordinator's LLM decides which agents to involve.
 */
public final class DelegationTools {

    public static final String ASK_ORDER_AGENT = "askOrderAgent";
    public static final String ASK_KNOWLEDGE_AGENT = "askKnowledgeAgent";

    private final OrderClientAgent orders;
    private final KnowledgeClientAgent knowledge;

    public DelegationTools(OrderClientAgent orders, KnowledgeClientAgent knowledge) {
        this.orders = orders;
        this.knowledge = knowledge;
    }

    @Tool(name = ASK_ORDER_AGENT, value = "Asks the order agent about a specific order or a customer's orders")
    public String askOrderAgent(@P("the question, including the order number or customer name") String question) {
        OrderClientAgent.Answer answer = orders.ask(question);
        return answer.needsInput() ? "The order agent needs more information: " + answer.text() : answer.text();
    }

    @Tool(name = ASK_KNOWLEDGE_AGENT, value = "Asks the knowledge agent about shop policies: returns, refunds, "
            + "shipping and warranty")
    public String askKnowledgeAgent(@P("the policy question") String question) {
        KnowledgeClientAgent.Answer answer = knowledge.ask(question);
        return answer.text() + (answer.sources().isEmpty() ? "" : " (sources: " + String.join(", ", answer.sources()) + ")");
    }
}
