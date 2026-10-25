package com.example.a2atest.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Minimal A2A server on the JDK's built-in HTTP server, hosting one agent:
 * <ul>
 *     <li>{@code GET  /.well-known/agent-card.json} returns the agent card (never needs an API key)</li>
 *     <li>{@code POST /} is the JSON-RPC 2.0 endpoint: {@code message/send} (blocking or not),
 *     {@code message/stream} (server-sent events), {@code tasks/get} and {@code tasks/cancel}</li>
 *     <li>{@code GET  /metrics} returns request, task and agent counters as JSON</li>
 * </ul>
 * Tasks run on virtual threads and are kept in a bounded in-memory store. A message carrying the {@code taskId}
 * of a task in {@code input-required} continues that task. {@link Options} add API-key authentication (when
 * the card declares it), a request size limit, a per-turn agent timeout and a cap on concurrently running
 * tasks.
 * <p>
 * Every task belongs to the principal that created it (the caller's API key, or {@code anonymous} when the
 * agent needs none): only that principal can read, continue or cancel it, and agents key chat memory by
 * {@link RequestContext#memoryKey()} so callers can't see each other's conversations. Errors returned to
 * callers never carry internal exception messages; those are logged with a reference id.
 */
public final class A2aServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(A2aServer.class);
    private static final String ANONYMOUS = "anonymous";
    private static final Set<String> METHODS = Set.of(A2a.METHOD_MESSAGE_SEND, A2a.METHOD_MESSAGE_STREAM,
            A2a.METHOD_TASKS_GET, A2a.METHOD_TASKS_CANCEL);

    /**
     * Server settings.
     *
     * @param apiKeys            keys accepted in {@value A2a#API_KEY_HEADER} when the card requires an API key
     * @param maxRequestBytes    larger request bodies are refused with HTTP 413
     * @param agentTimeout       a turn that hasn't settled after this long fails with "timed out"
     * @param maxTasks           size of the task store; the oldest tasks are dropped beyond it
     * @param maxConcurrentTasks turns running at once; further messages are refused with HTTP 429, which
     *                           bounds the LLM cost a caller can cause with non-blocking sends
     */
    public record Options(Set<String> apiKeys, int maxRequestBytes, Duration agentTimeout, int maxTasks,
                          int maxConcurrentTasks) {

        public static Options defaults() {
            return new Options(Set.of(), 1024 * 1024, Duration.ofMinutes(2), 1000, 64);
        }

        public Options withApiKeys(String... keys) {
            return new Options(Set.of(keys), maxRequestBytes, agentTimeout, maxTasks, maxConcurrentTasks);
        }

        public Options withMaxRequestBytes(int bytes) {
            return new Options(apiKeys, bytes, agentTimeout, maxTasks, maxConcurrentTasks);
        }

        public Options withAgentTimeout(Duration timeout) {
            return new Options(apiKeys, maxRequestBytes, timeout, maxTasks, maxConcurrentTasks);
        }

        public Options withMaxConcurrentTasks(int tasks) {
            return new Options(apiKeys, maxRequestBytes, agentTimeout, maxTasks, tasks);
        }
    }

    private final A2aAgent agent;
    private final Options options;
    private final TaskStore tasks;
    private final HttpServer http;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore running;
    private final List<byte[]> keyDigests;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    /** Starts a server on localhost with default options; pass port 0 to pick a free port. */
    public A2aServer(int port, A2aAgent agent) throws IOException {
        this(port, agent, Options.defaults());
    }

    public A2aServer(int port, A2aAgent agent, Options options) throws IOException {
        this.agent = agent;
        this.options = options;
        this.tasks = new TaskStore(options.maxTasks());
        this.running = new Semaphore(options.maxConcurrentTasks());
        this.keyDigests = options.apiKeys().stream().map(A2aServer::sha256).toList();
        if (agent.card("http://localhost/").requiresApiKey() && keyDigests.isEmpty()) {
            // fail closed and loudly instead of starting a server that refuses every call
            throw new IllegalArgumentException(agent.card("http://localhost/").name()
                    + " requires an API key but no keys are configured (Options.withApiKeys)");
        }
        http = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        http.createContext(A2a.AGENT_CARD_PATH, this::agentCard);
        http.createContext("/metrics", this::metrics);
        http.createContext("/", this::jsonRpc);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        log.info("A2A server listening on {} hosting {}", baseUri(), card().name());
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + http.getAddress().getPort());
    }

    private A2a.AgentCard card() {
        return agent.card(baseUri() + "/");
    }

    private void agentCard(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            sendJson(ex, 200, card());
        }
    }

    private void metrics(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (authenticate(ex) == null) {
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Long> requests = new LinkedHashMap<>();
            counters.forEach((name, n) -> requests.put(name, n.sum()));
            body.put("agent", card().name());
            body.put("requests", requests);
            body.put("tasks", tasks.countByState());
            body.put("runningTasks", options.maxConcurrentTasks() - running.availablePermits());
            body.put("agentMetrics", agent.metrics());
            sendJson(ex, 200, body);
        }
    }

    private void jsonRpc(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod()) || !"/".equals(ex.getRequestURI().getPath())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String principal = authenticate(ex);
            if (principal == null) {
                return;
            }
            byte[] body = ex.getRequestBody().readNBytes(options.maxRequestBytes() + 1);
            if (body.length > options.maxRequestBytes()) {
                count("rejected.tooLarge");
                sendJson(ex, 413, A2a.JsonRpcResponse.failure(null, new A2a.JsonRpcError(
                        A2a.JsonRpcError.INVALID_REQUEST, "Request larger than " + options.maxRequestBytes() + " bytes")));
                return;
            }
            A2a.JsonRpcRequest request;
            try {
                request = A2a.JSON.readValue(body, A2a.JsonRpcRequest.class);
            } catch (IOException e) {
                // the parser's message quotes the input; don't echo it back
                sendError(ex, null, A2a.JsonRpcError.PARSE_ERROR, "Parse error");
                return;
            }
            if (!"2.0".equals(request.jsonrpc()) || request.method() == null) {
                sendError(ex, request.id(), A2a.JsonRpcError.INVALID_REQUEST, "Not a JSON-RPC 2.0 request");
                return;
            }
            // count only known method names, so callers can't grow the counter map without bound
            count("method." + (METHODS.contains(request.method()) ? request.method() : "unknown"));
            switch (request.method()) {
                case A2a.METHOD_MESSAGE_SEND -> sendMessage(ex, request, principal);
                case A2a.METHOD_MESSAGE_STREAM -> streamMessage(ex, request, principal);
                case A2a.METHOD_TASKS_GET -> getTask(ex, request, principal);
                case A2a.METHOD_TASKS_CANCEL -> cancelTask(ex, request, principal);
                default -> sendError(ex, request.id(), A2a.JsonRpcError.METHOD_NOT_FOUND, "Method not found");
            }
        }
    }

    /**
     * Returns the caller's principal: {@code anonymous} when the card needs no API key, else an id derived from
     * the key. Sends HTTP 401 and returns null for a missing or unknown key. Keys are compared as SHA-256
     * digests in constant time, and every configured key is checked, so timing reveals nothing about them.
     */
    private String authenticate(HttpExchange ex) throws IOException {
        if (!card().requiresApiKey()) {
            return ANONYMOUS;
        }
        String key = ex.getRequestHeaders().getFirst(A2a.API_KEY_HEADER);
        if (key != null) {
            byte[] digest = sha256(key);
            boolean match = false;
            for (byte[] known : keyDigests) {
                match |= MessageDigest.isEqual(known, digest);
            }
            if (match) {
                return "key-" + HexFormat.of().formatHex(digest, 0, 8);
            }
        }
        count("rejected.unauthorized");
        ex.getResponseHeaders().set("WWW-Authenticate", "ApiKey header=\"" + A2a.API_KEY_HEADER + "\"");
        sendJson(ex, 401, A2a.JsonRpcResponse.failure(null, new A2a.JsonRpcError(
                A2a.JsonRpcError.INVALID_REQUEST, "Missing or invalid " + A2a.API_KEY_HEADER)));
        return null;
    }

    // ---- message/send and message/stream ---------------------------------------------------------------

    private void sendMessage(HttpExchange ex, A2a.JsonRpcRequest request, String principal) throws IOException {
        A2a.MessageSendParams params = params(request, A2a.MessageSendParams.class);
        TaskStore.TaskRecord task = startTurn(ex, request, params, principal, null);
        if (task == null) {
            return;
        }
        if (params.blocking()) {
            awaitSettled(task);
        }
        sendJson(ex, 200, A2a.JsonRpcResponse.success(request.id(), task.snapshot(historyLength(params))));
    }

    private void streamMessage(HttpExchange ex, A2a.JsonRpcRequest request, String principal) throws IOException {
        if (!card().supportsStreaming()) {
            sendError(ex, request.id(), A2a.JsonRpcError.UNSUPPORTED_OPERATION,
                    card().name() + " does not support streaming");
            return;
        }
        A2a.MessageSendParams params = params(request, A2a.MessageSendParams.class);
        BlockingQueue<A2a.StreamEvent> events = new LinkedBlockingQueue<>();
        Consumer<A2a.StreamEvent> listener = events::add;
        TaskStore.TaskRecord task = startTurn(ex, request, params, principal, listener);
        if (task == null) {
            return;
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                // the task snapshot comes first, then every update until the final one
                writeEvent(out, request.id(), task.snapshot(historyLength(params)));
                long deadline = System.nanoTime() + options.agentTimeout().plusSeconds(5).toNanos();
                while (true) {
                    A2a.StreamEvent event = events.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (event == null) {
                        break;
                    }
                    writeEvent(out, request.id(), event);
                    if (event instanceof A2a.TaskStatusUpdateEvent status && status.isFinal()) {
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            task.unsubscribe(listener);
        }
    }

    /**
     * Validates the message, creates the task (or continues the caller's {@code input-required} task it names)
     * and starts the agent on it. Sends an error and returns null if the message can't be accepted.
     */
    private TaskStore.TaskRecord startTurn(HttpExchange ex, A2a.JsonRpcRequest request, A2a.MessageSendParams params,
                                           String principal, Consumer<A2a.StreamEvent> listener) throws IOException {
        A2a.Message message = params == null ? null : params.message();
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            sendError(ex, request.id(), A2a.JsonRpcError.INVALID_PARAMS, "params.message with parts is required");
            return null;
        }
        if (!A2a.isValidId(message.contextId()) || !A2a.isValidId(message.taskId())) {
            sendError(ex, request.id(), A2a.JsonRpcError.INVALID_PARAMS,
                    "contextId and taskId must be 1-128 characters of [A-Za-z0-9._:-]");
            return null;
        }
        if (!running.tryAcquire()) {
            count("rejected.busy");
            sendJson(ex, 429, A2a.JsonRpcResponse.failure(request.id(), new A2a.JsonRpcError(
                    A2a.JsonRpcError.SERVER_BUSY, "Too many tasks running; retry later")));
            return null;
        }
        boolean started = false;
        try {
            TaskStore.TaskRecord task;
            List<A2a.Message> history;
            if (message.taskId() != null) {
                task = tasks.get(message.taskId(), principal);
                if (task == null) {
                    sendError(ex, request.id(), A2a.JsonRpcError.TASK_NOT_FOUND, "Task not found");
                    return null;
                }
                if (message.contextId() != null && !message.contextId().equals(task.contextId())) {
                    sendError(ex, request.id(), A2a.JsonRpcError.INVALID_PARAMS,
                            "contextId does not match the task's context");
                    return null;
                }
                history = task.history();
                if (!task.beginTurn(message, true)) {
                    sendError(ex, request.id(), A2a.JsonRpcError.INVALID_PARAMS,
                            "Task " + task.id() + " is " + task.state().wire() + " and does not accept input");
                    return null;
                }
            } else {
                task = tasks.create(message.contextId() != null ? message.contextId() : UUID.randomUUID().toString(),
                        principal);
                history = List.of();
                task.beginTurn(message, false);
            }

            RequestContext context = new RequestContext(message, task.id(), task.contextId(), history, principal);
            if (listener != null) {
                task.subscribe(listener);
            }
            TaskUpdater updater = new TaskUpdater(task);
            task.execution(workers.submit(() -> run(context, task, updater)));
            started = true;
            // orTimeout on a copy, so the task's own future is only ever completed by a status change
            task.settled().copy().orTimeout(options.agentTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(e -> {
                        if (e instanceof TimeoutException && task.cancelForTimeout(options.agentTimeout())) {
                            count("tasks.timedOut");
                            log.warn("Task {} of {} timed out after {}", task.id(), card().name(), options.agentTimeout());
                        }
                        return null;
                    });
            return task;
        } finally {
            if (!started) {
                running.release();
            }
        }
    }

    private void run(RequestContext context, TaskStore.TaskRecord task, TaskUpdater updater) {
        MDC.put("taskId", task.id());
        MDC.put("contextId", task.contextId());
        long start = System.nanoTime();
        try {
            agent.execute(context, updater);
            if (!task.state().isTerminal() && !task.state().isInterrupted()) {
                updater.fail("Agent returned without completing the task");
            }
        } catch (RuntimeException e) {
            if (!updater.isCanceled()) {
                // the exception may carry internals (paths, provider errors); the caller only gets a reference
                String reference = UUID.randomUUID().toString().substring(0, 8);
                log.warn("Agent {} failed on task {} (context {}), reference {}", card().name(), task.id(),
                        task.contextId(), reference, e);
                updater.fail("Internal error (reference " + reference + ")");
            }
        } finally {
            running.release();
            log.info("Task {} (context {}) of {} -> {} in {} ms", task.id(), task.contextId(), card().name(),
                    task.state().wire(), Duration.ofNanos(System.nanoTime() - start).toMillis());
            MDC.clear();
        }
    }

    private void awaitSettled(TaskStore.TaskRecord task) {
        try {
            task.settled().get(options.agentTimeout().plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // the timeout handler has already failed the task; return its current state
        }
    }

    // ---- tasks/get and tasks/cancel --------------------------------------------------------------------

    private void getTask(HttpExchange ex, A2a.JsonRpcRequest request, String principal) throws IOException {
        A2a.TaskQueryParams params = params(request, A2a.TaskQueryParams.class);
        TaskStore.TaskRecord task = params == null ? null : tasks.get(params.id(), principal);
        if (task == null) {
            sendError(ex, request.id(), A2a.JsonRpcError.TASK_NOT_FOUND, "Task not found");
            return;
        }
        sendJson(ex, 200, A2a.JsonRpcResponse.success(request.id(), task.snapshot(params.historyLength())));
    }

    private void cancelTask(HttpExchange ex, A2a.JsonRpcRequest request, String principal) throws IOException {
        A2a.TaskIdParams params = params(request, A2a.TaskIdParams.class);
        if (params == null || params.id() == null) {
            sendError(ex, request.id(), A2a.JsonRpcError.INVALID_PARAMS, "params.id is required");
            return;
        }
        TaskStore.TaskRecord task = tasks.get(params.id(), principal);
        if (task == null) {
            sendError(ex, request.id(), A2a.JsonRpcError.TASK_NOT_FOUND, "Task not found");
            return;
        }
        if (!task.cancel()) {
            sendError(ex, request.id(), A2a.JsonRpcError.TASK_NOT_CANCELABLE,
                    "Task " + task.id() + " is already " + task.state().wire());
            return;
        }
        count("tasks.canceled");
        sendJson(ex, 200, A2a.JsonRpcResponse.success(request.id(), task.snapshot(null)));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static <T> T params(A2a.JsonRpcRequest request, Class<T> type) {
        JsonNode params = request.params();
        if (params == null || params.isNull()) {
            return null;
        }
        try {
            return A2a.JSON.treeToValue(params, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer historyLength(A2a.MessageSendParams params) {
        return params.configuration() == null ? null : params.configuration().historyLength();
    }

    private void count(String name) {
        counters.computeIfAbsent(name, n -> new LongAdder()).increment();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeEvent(OutputStream out, Object id, A2a.StreamEvent event) throws IOException {
        byte[] json = A2a.JSON.writeValueAsBytes(A2a.JsonRpcResponse.success(id, event));
        out.write("data: ".getBytes(StandardCharsets.UTF_8));
        out.write(json);
        out.write("\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendError(HttpExchange ex, Object id, int code, String message) throws IOException {
        // JSON-RPC errors are still delivered with HTTP 200
        sendJson(ex, 200, A2a.JsonRpcResponse.failure(id, new A2a.JsonRpcError(code, message)));
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = A2a.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        http.stop(0);
        workers.shutdownNow();
    }
}
