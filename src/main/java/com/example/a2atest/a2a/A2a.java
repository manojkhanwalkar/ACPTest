package com.example.a2atest.a2a;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Data model of the Agent2Agent protocol (A2A, https://a2a-protocol.org) over its JSON-RPC 2.0 binding.
 * It covers {@code message/send}, {@code message/stream}, {@code tasks/get} and {@code tasks/cancel},
 * text and data parts, artifacts, streaming events and API-key security schemes. Field names are camelCase,
 * as in the A2A specification (e.g. {@code messageId}, {@code contextId}).
 */
public final class A2a {

    public static final String PROTOCOL_VERSION = "0.3.0";
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String APPLICATION_JSON = "application/json";

    public static final String METHOD_MESSAGE_SEND = "message/send";
    public static final String METHOD_MESSAGE_STREAM = "message/stream";
    public static final String METHOD_TASKS_GET = "tasks/get";
    public static final String METHOD_TASKS_CANCEL = "tasks/cancel";

    /** Header carrying the API key for agents whose card declares {@link #apiKeySecurity()}. */
    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String API_KEY_SCHEME = "apiKey";

    /** Message metadata key a client can set to pick one of the agent's skills explicitly. */
    public static final String SKILL_METADATA_KEY = "skill";

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
    public record AgentSkill(String id, String name, String description, List<String> tags, List<String> examples,
                             List<String> inputModes, List<String> outputModes) {

        public AgentSkill(String id, String name, String description, List<String> tags) {
            this(id, name, description, tags, null, null, null);
        }
    }

    public record AgentCapabilities(boolean streaming, boolean pushNotifications) {
    }

    /** One entry of {@code securitySchemes}; only API keys ({@code "type": "apiKey"}) are used here. */
    public record SecurityScheme(String type, String in, String name, String description) {
    }

    /** Self-description an agent publishes at {@value #AGENT_CARD_PATH}. */
    public record AgentCard(String protocolVersion, String name, String description, String url,
                            String preferredTransport, String version, AgentCapabilities capabilities,
                            List<String> defaultInputModes, List<String> defaultOutputModes,
                            List<AgentSkill> skills, Map<String, SecurityScheme> securitySchemes,
                            List<Map<String, List<String>>> security) {

        public AgentCard(String protocolVersion, String name, String description, String url,
                         String preferredTransport, String version, AgentCapabilities capabilities,
                         List<String> defaultInputModes, List<String> defaultOutputModes, List<AgentSkill> skills) {
            this(protocolVersion, name, description, url, preferredTransport, version, capabilities,
                    defaultInputModes, defaultOutputModes, skills, null, null);
        }

        /** A JSON-RPC card for {@code url} with this project's protocol version and agent version 1.0.0. */
        public static AgentCard of(String name, String description, String url, boolean streaming,
                                   List<String> modes, List<AgentSkill> skills) {
            return new AgentCard(PROTOCOL_VERSION, name, description, url, "JSONRPC", "1.0.0",
                    new AgentCapabilities(streaming, false), modes, modes, skills);
        }

        /** This card, additionally requiring the API key header on every JSON-RPC call. */
        public AgentCard withApiKeySecurity() {
            return new AgentCard(protocolVersion, name, description, url, preferredTransport, version, capabilities,
                    defaultInputModes, defaultOutputModes, skills, Map.of(API_KEY_SCHEME, apiKeySecurity()),
                    List.of(Map.of(API_KEY_SCHEME, List.of())));
        }

        @JsonIgnore
        public boolean requiresApiKey() {
            return securitySchemes != null && securitySchemes.containsKey(API_KEY_SCHEME);
        }

        @JsonIgnore
        public boolean supportsStreaming() {
            return capabilities != null && capabilities.streaming();
        }
    }

    public static SecurityScheme apiKeySecurity() {
        return new SecurityScheme("apiKey", "header", API_KEY_HEADER, "API key issued to calling agents");
    }

    // ---- Messages and tasks ----------------------------------------------------------------------------

    /**
     * One piece of message content: {@code "kind": "text"} with {@code text}, or {@code "kind": "data"} with a
     * structured JSON {@code data} object.
     */
    public record Part(String kind, String text, JsonNode data, Map<String, Object> metadata) {

        public Part(String kind, String text, Map<String, Object> metadata) {
            this(kind, text, null, metadata);
        }

        public static Part text(String text) {
            return new Part("text", text, null, null);
        }

        /** A data part holding {@code value} serialized to JSON (a record, map or list). */
        public static Part data(Object value) {
            return new Part("data", null, JSON.valueToTree(value), null);
        }

        @JsonIgnore
        public boolean isText() {
            return "text".equals(kind);
        }

        @JsonIgnore
        public boolean isData() {
            return "data".equals(kind);
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
                          String taskId, Map<String, Object> metadata) implements StreamEvent {

        public Message(String kind, String messageId, Role role, List<Part> parts, String contextId, String taskId) {
            this(kind, messageId, role, parts, contextId, taskId, null);
        }

        public static Message user(String text, String contextId) {
            return user(List.of(Part.text(text)), contextId, null, null);
        }

        /** A user message with any parts, optionally continuing {@code taskId} and carrying metadata. */
        public static Message user(List<Part> parts, String contextId, String taskId, Map<String, Object> metadata) {
            return new Message("message", UUID.randomUUID().toString(), Role.USER, parts, contextId, taskId,
                    metadata);
        }

        public static Message agent(String text, String contextId, String taskId) {
            return agent(List.of(Part.text(text)), contextId, taskId);
        }

        public static Message agent(List<Part> parts, String contextId, String taskId) {
            return new Message("message", UUID.randomUUID().toString(), Role.AGENT, parts, contextId, taskId, null);
        }

        /** Concatenated text of all text parts. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Part part : parts) {
                if (part.isText() && part.text() != null) {
                    sb.append(part.text());
                }
            }
            return sb.toString();
        }

        /** The first data part's JSON, or {@code null} if the message has none. */
        public JsonNode data() {
            for (Part part : parts) {
                if (part.isData() && part.data() != null) {
                    return part.data();
                }
            }
            return null;
        }

        /** The value of a metadata entry as a string, or {@code null}. */
        public String metadata(String key) {
            Object value = metadata == null ? null : metadata.get(key);
            return value == null ? null : value.toString();
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

        /** No further updates follow a terminal state. */
        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELED || this == FAILED || this == REJECTED;
        }

        /** The task waits for the client: a blocking call returns, and the client continues the task later. */
        public boolean isInterrupted() {
            return this == INPUT_REQUIRED || this == AUTH_REQUIRED;
        }
    }

    /** Current state of a task, optionally with the agent message that accompanies it. */
    public record TaskStatus(TaskState state, Message message, Instant timestamp) {
    }

    /** An output the agent produced for a task (a report, a document, a data set). */
    public record Artifact(String artifactId, String name, String description, List<Part> parts) {

        /** Concatenated text of all text parts. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Part part : parts) {
                if (part.isText() && part.text() != null) {
                    sb.append(part.text());
                }
            }
            return sb.toString();
        }
    }

    /** A unit of work the agent performs for a client, carrying the conversation history and its artifacts. */
    public record Task(String kind, String id, String contextId, TaskStatus status, List<Message> history,
                       List<Artifact> artifacts) implements StreamEvent {

        public Task(String kind, String id, String contextId, TaskStatus status, List<Message> history) {
            this(kind, id, contextId, status, history, null);
        }

        public static Task of(String id, String contextId, TaskState state, Message request, Message response) {
            return new Task("task", id, contextId, new TaskStatus(state, response, Instant.now()),
                    List.of(request, response));
        }

        /** Text of the agent message in the current status, or {@code null}. */
        public String statusText() {
            return status.message() == null ? null : status.message().text();
        }

        /** The artifact with the given name, or {@code null}. */
        public Artifact artifact(String name) {
            if (artifacts != null) {
                for (Artifact artifact : artifacts) {
                    if (name.equals(artifact.name())) {
                        return artifact;
                    }
                }
            }
            return null;
        }
    }

    // ---- Streaming events (message/stream) ---------------------------------------------------------------

    /** One server-sent event of {@code message/stream}: a task snapshot, a message, or an update to a task. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind",
            visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Task.class, name = "task"),
            @JsonSubTypes.Type(value = Message.class, name = "message"),
            @JsonSubTypes.Type(value = TaskStatusUpdateEvent.class, name = "status-update"),
            @JsonSubTypes.Type(value = TaskArtifactUpdateEvent.class, name = "artifact-update")
    })
    public sealed interface StreamEvent permits Task, Message, TaskStatusUpdateEvent, TaskArtifactUpdateEvent {

        String kind();
    }

    /** The task's status changed; {@code final} marks the last event of the stream. */
    public record TaskStatusUpdateEvent(String kind, String taskId, String contextId, TaskStatus status,
                                        @JsonProperty("final") boolean isFinal) implements StreamEvent {

        public TaskStatusUpdateEvent(String taskId, String contextId, TaskStatus status, boolean isFinal) {
            this("status-update", taskId, contextId, status, isFinal);
        }
    }

    /** An artifact was created or, with {@code append}, a chunk was added to it. */
    public record TaskArtifactUpdateEvent(String kind, String taskId, String contextId, Artifact artifact,
                                          boolean append, boolean lastChunk) implements StreamEvent {

        public TaskArtifactUpdateEvent(String taskId, String contextId, Artifact artifact, boolean append,
                                       boolean lastChunk) {
            this("artifact-update", taskId, contextId, artifact, append, lastChunk);
        }
    }

    // ---- Method params ---------------------------------------------------------------------------------

    /** Options of {@code message/send}; {@code blocking=false} returns as soon as the task is accepted. */
    public record MessageSendConfiguration(List<String> acceptedOutputModes, Integer historyLength,
                                           Boolean blocking) {

        public static MessageSendConfiguration nonBlocking() {
            return new MessageSendConfiguration(null, null, false);
        }
    }

    /** Params of {@code message/send} and {@code message/stream}. */
    public record MessageSendParams(Message message, MessageSendConfiguration configuration,
                                    Map<String, Object> metadata) {

        public MessageSendParams(Message message) {
            this(message, null, null);
        }

        @JsonIgnore
        public boolean blocking() {
            return configuration == null || configuration.blocking() == null || configuration.blocking();
        }
    }

    /** Params of {@code tasks/get}; {@code historyLength} limits the returned history to its last N messages. */
    public record TaskQueryParams(String id, Integer historyLength) {
    }

    /** Params of {@code tasks/cancel}. */
    public record TaskIdParams(String id) {
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

    /** JSON-RPC error: the standard codes (-32700 ... -32603) and the A2A-specific ones (-32001 ...). */
    public record JsonRpcError(int code, String message) {

        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
        public static final int TASK_NOT_FOUND = -32001;
        public static final int TASK_NOT_CANCELABLE = -32002;
        public static final int UNSUPPORTED_OPERATION = -32004;
        /** Implementation-defined (JSON-RPC reserves -32000 to -32099): too many tasks running. */
        public static final int SERVER_BUSY = -32000;
    }

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /**
     * True for null or an id of 1-128 characters from {@code [A-Za-z0-9._:-]}. Client-chosen ids end up in
     * memory keys, logs and file names, so the server refuses anything else.
     */
    public static boolean isValidId(String id) {
        return id == null || VALID_ID.matcher(id).matches();
    }

    /** The last {@code n} elements of {@code list} (all of them when {@code n} is null or not smaller). */
    static <T> List<T> tail(List<T> list, Integer n) {
        if (n == null || n >= list.size()) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>(list.subList(list.size() - Math.max(n, 0), list.size()));
    }
}
