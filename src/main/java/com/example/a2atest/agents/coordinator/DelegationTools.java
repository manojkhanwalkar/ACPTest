package com.example.a2atest.agents.coordinator;

import com.example.a2atest.agents.knowledge.KnowledgeClientAgent;
import com.example.a2atest.agents.order.OrderClientAgent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * LangChain4j tools of the {@link CoordinatorAgent}. Each tool is a call to another A2A agent through its
 * client agent, so the coordinator's LLM decides which agents to involve.
 * <p>
 * Every coordinator conversation gets its own conversation at the other agents (a context id derived from the
 * coordinator's memory key), so one caller's questions never become part of another caller's answers. Answers
 * from other agents are untrusted input to the coordinator's LLM, so they are cut to
 * {@value #MAX_ANSWER_CHARS} characters.
 */
public final class DelegationTools {

    public static final String ASK_ORDER_AGENT = "askOrderAgent";
    public static final String ASK_KNOWLEDGE_AGENT = "askKnowledgeAgent";
    static final int MAX_ANSWER_CHARS = 2000;

    private final OrderClientAgent orders;
    private final KnowledgeClientAgent knowledge;

    public DelegationTools(OrderClientAgent orders, KnowledgeClientAgent knowledge) {
        this.orders = orders;
        this.knowledge = knowledge;
    }

    @Tool(name = ASK_ORDER_AGENT, value = "Asks the order agent about a specific order or a customer's orders")
    public String askOrderAgent(@ToolMemoryId String memoryKey,
                                @P("the question, including the order number or customer name") String question) {
        OrderClientAgent.Answer answer = orders.ask(question, downstreamContext(memoryKey));
        return limit(answer.needsInput() ? "The order agent needs more information: " + answer.text() : answer.text());
    }

    @Tool(name = ASK_KNOWLEDGE_AGENT, value = "Asks the knowledge agent about shop policies: returns, refunds, "
            + "shipping and warranty")
    public String askKnowledgeAgent(@ToolMemoryId String memoryKey, @P("the policy question") String question) {
        KnowledgeClientAgent.Answer answer = knowledge.ask(question, downstreamContext(memoryKey));
        return limit(answer.text()
                + (answer.sources().isEmpty() ? "" : " (sources: " + String.join(", ", answer.sources()) + ")"));
    }

    /** A stable context id for the other agents that doesn't reveal the caller's memory key. */
    static String downstreamContext(String memoryKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(memoryKey.getBytes(StandardCharsets.UTF_8));
            return "coordinator-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String limit(String text) {
        return text.length() <= MAX_ANSWER_CHARS ? text : text.substring(0, MAX_ANSWER_CHARS) + " [truncated]";
    }
}
