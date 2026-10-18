package com.example.a2atest.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data model of the Agent2Agent protocol (A2A, https://a2a-protocol.org) over its JSON-RPC 2.0 binding.
 * Only the subset needed for a blocking {@code message/send} call is modelled. Field names are camelCase,
 * as in the A2A specification (e.g. {@code messageId}, {@code contextId}).
 */
public final class A2a {

    public static final String PROTOCOL_VERSION = "0.3.0";
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String METHOD_MESSAGE_SEND = "message/send";

    /** Shared JSON mapper configured for the A2A wire format. */
    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private A2a() {
    }

    // ---- Agent discovery -------------------------------------------------------------------------------

    /** A capability the agent advertises in its card. */
    public record AgentSkill(String id, String name, String description, List<String> tags) {
    }

    public record AgentCapabilities(boolean streaming, boolean pushNotifications) {
    }

    /** Self-description an agent publishes at {@value #AGENT_CARD_PATH}. */
    public record AgentCard(String protocolVersion, String name, String description, String url,
                            String preferredTransport, String version, AgentCapabilities capabilities,
                            List<String> defaultInputModes, List<String> defaultOutputModes,
                            List<AgentSkill> skills) {
    }

    // ---- Messages and tasks ----------------------------------------------------------------------------

    /** One piece of message content. Only text parts ({@code "kind": "text"}) are modelled. */
    public record Part(String kind, String text, Map<String, Object> metadata) {

        public static Part text(String text) {
            return new Part("text", text, null);
        }
    }

    public enum Role {
        USER("user"), AGENT("agent");

        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** A single turn of communication between a client and an agent. */
    public record Message(String kind, String messageId, Role role, List<Part> parts, String contextId,
                          String taskId) {

        public static Message user(String text, String contextId) {
            return new Message("message", UUID.randomUUID().toString(), Role.USER, List.of(Part.text(text)),
                    contextId, null);
        }

        public static Message agent(String text, String contextId, String taskId) {
            return new Message("message", UUID.randomUUID().toString(), Role.AGENT, List.of(Part.text(text)),
                    contextId, taskId);
        }

        /** Concatenated text of all text parts. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Part part : parts) {
                if ("text".equals(part.kind()) && part.text() != null) {
                    sb.append(part.text());
                }
            }
            return sb.toString();
        }
    }

    public enum TaskState {
        SUBMITTED("submitted"), WORKING("working"), INPUT_REQUIRED("input-required"), COMPLETED("completed"),
        CANCELED("canceled"), FAILED("failed"), REJECTED("rejected"), AUTH_REQUIRED("auth-required"),
        UNKNOWN("unknown");

        private final String wire;

        TaskState(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Current state of a task, optionally with the agent message that accompanies it. */
    public record TaskStatus(TaskState state, Message message, Instant timestamp) {
    }

    /** A unit of work the agent performs for a client, carrying the conversation history. */
    public record Task(String kind, String id, String contextId, TaskStatus status, List<Message> history) {

        public static Task of(String id, String contextId, TaskState state, Message request, Message response) {
            return new Task("task", id, contextId, new TaskStatus(state, response, Instant.now()),
                    List.of(request, response));
        }
    }

    /** Params of {@code message/send}. */
    public record MessageSendParams(Message message) {
    }

    // ---- JSON-RPC 2.0 envelope -------------------------------------------------------------------------

    public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params) {

        public static JsonRpcRequest of(String method, Object params) {
            return new JsonRpcRequest("2.0", UUID.randomUUID().toString(), method, JSON.valueToTree(params));
        }
    }

    public record JsonRpcResponse(String jsonrpc, Object id, JsonNode result, JsonRpcError error) {

        public static JsonRpcResponse success(Object id, Object result) {
            return new JsonRpcResponse("2.0", id, JSON.valueToTree(result), null);
        }

        public static JsonRpcResponse failure(Object id, JsonRpcError error) {
            return new JsonRpcResponse("2.0", id, null, error);
        }
    }

    /** JSON-RPC error; codes are the standard JSON-RPC ones (-32700 ... -32603). */
    public record JsonRpcError(int code, String message) {

        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
    }
}
