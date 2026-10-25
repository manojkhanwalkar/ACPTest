# ACPTest

Two LangChain4j agents, **Agent1** and **Agent2**, that exchange a request and a response over the
[Agent Communication Protocol (ACP)](https://agentcommunicationprotocol.dev). No LLM is connected yet.

## How it works

```
Agent1 ──GET /agents/agent2──▶  AcpServer (hosts Agent2)
       ──POST /runs (sync)───▶    └─ Agent2 → LangChain4j AiService → PlaceholderChatModel
       ◀── Run{status: completed, output: [agent/agent2 message]}
```

| Class | Role |
|---|---|
| `acp/Acp` | ACP data model (Message, MessagePart, Run, RunCreateRequest, AgentManifest, Error), snake_case JSON |
| `acp/AcpServer` | ACP REST server on the JDK HTTP server: `GET /ping`, `GET /agents`, `GET /agents/{name}`, `POST /runs` (sync mode) |
| `acp/AcpClient` | ACP client: agent discovery and synchronous runs |
| `agent/Agent1` | Requesting agent: composes a request with its assistant and sends it to Agent2 as an ACP run |
| `agent/Agent2` | Responding agent: hosted on the ACP server, answers each run with its assistant |
| `agent/Assistant` | LangChain4j AI service interface used by both agents (chat memory per ACP session) |
| `agent/PlaceholderChatModel` | A `ChatModel` with a fixed rule, standing in for the LLM |

## Run

```bash
./mvnw verify            # build + tests
./mvnw compile exec:java # start the server on port 8000 and run the exchange
```

Example output:

```
Agents available at http://localhost:8000: [agent2]
Agent1 -> Agent2 : Hi Agent2, what is the status of order #42?
Agent2 -> Agent1 : Hello Agent1, Agent2 received your request: "Hi Agent2, what is the status of order #42?"
Run 0bcbfb13-... status=completed
```

## Adding an LLM later

Both agents take a LangChain4j `ChatModel` in their constructor. Add a provider dependency (for example
`langchain4j-ollama`) and pass the real model in place of `PlaceholderChatModel`:

```java
ChatModel llm = OllamaChatModel.builder().baseUrl("http://localhost:11434").modelName("llama3.2").build();
new Agent2(llm);
new Agent1(client, llm);
```

---

# A2A example (`com.example.a2atest`)

The same two-agent exchange over the [Agent2Agent protocol (A2A)](https://a2a-protocol.org), using its
JSON-RPC 2.0 binding. It doesn't share any code with the ACP example.

```
Agent1 ──GET /.well-known/agent-card.json──▶  A2aServer (hosts Agent2)
       ──POST / {"method":"message/send"}──▶    └─ Agent2 → LangChain4j AiService → PlaceholderChatModel
       ◀── JSON-RPC result: Task{status: {state: completed, message: agent reply}, history}
```

| Class | Role |
|---|---|
| `a2a/A2a` | A2A data model (AgentCard, Message, Part, Task, Artifact, streaming events) and the JSON-RPC 2.0 envelope, camelCase JSON |
| `a2a/A2aServer` | A2A server on the JDK HTTP server (see [A2A protocol layer](#a2a-protocol-layer) below) |
| `a2a/A2aClient` | A2A client: agent card, `message/send`, `message/stream`, `tasks/get`, `tasks/cancel`, optional API key |
| `agent/Agent1` | Requesting agent: discovers Agent2 through its card and sends a message, using the A2A `contextId` as its conversation key |
| `agent/Agent2` | Responding agent: its answer becomes the agent message of a completed task (chat memory bounded by `ConversationMemory`) |

Run it:

```bash
./mvnw compile exec:java -Dexec.mainClass=com.example.a2atest.A2aTestApp   # port 9999
```

```
Agent card at http://localhost:9999/.well-known/agent-card.json: agent2 (Answers requests sent by other agents) -> http://localhost:9999/
Agent1 -> Agent2 : Hi Agent2, what is the status of order #42?
Agent2 -> Agent1 : Hello Agent1, Agent2 received your request: "Hi Agent2, what is the status of order #42?"
Task 2533dd3e-... state=completed
```

---

# Feature agents (`com.example.a2atest.agents`)

Six A2A agents that build on the Agent1/Agent2 example. Each one shows a **logical group of features**, so
you can read one agent to learn one topic. Every agent runs on its own `A2aServer`. Each has a **client
agent** (`…ClientAgent`) that wraps the A2A calls into a typed Java API.

| Agent (card name) | Features it demonstrates | Client agent |
|---|---|---|
| [`OrderAgent`](src/main/java/com/example/a2atest/agents/order/OrderAgent.java) (`order-agent`) | LangChain4j **tools**, a **system prompt**, the A2A **`input-required`** state, and multi-turn continuation of a task | `OrderClientAgent` |
| [`CatalogAgent`](src/main/java/com/example/a2atest/agents/catalog/CatalogAgent.java) (`catalog-agent`) | **Several skills with routing**, and **structured data**: JSON data parts plus LangChain4j structured output into a record | `CatalogClientAgent` |
| [`ReportAgent`](src/main/java/com/example/a2atest/agents/report/ReportAgent.java) (`report-agent`) | **Streaming** (`message/stream`, SSE, token streaming into artifact chunks) and the **task lifecycle**: non-blocking send, `tasks/get`, `tasks/cancel` | `ReportClientAgent` |
| [`KnowledgeAgent`](src/main/java/com/example/a2atest/agents/knowledge/KnowledgeAgent.java) (`knowledge-agent`) | **RAG**: a LangChain4j `ContentRetriever` over policy documents, with the sources returned in the reply | `KnowledgeClientAgent` |
| [`SupportAgent`](src/main/java/com/example/a2atest/agents/support/SupportAgent.java) (`support-agent`) | **Hardening**: API-key **auth**, input/output **guardrails**, request **size limit** and **timeout**, bounded and **persistent memory**, **metrics** | `SupportClientAgent` |
| [`CoordinatorAgent`](src/main/java/com/example/a2atest/agents/coordinator/CoordinatorAgent.java) (`coordinator-agent`) | **Agent-to-agent delegation**: its tools *are* the Order and Knowledge client agents | `CoordinatorClientAgent` |

Shared building blocks:

| Package | Contents |
|---|---|
| `a2a` | The protocol layer: `A2aServer`, `A2aClient`, `A2aAgent` (agent SPI), `RequestContext`, `TaskUpdater`, `TaskStore` |
| `llm` | `LlmConfig` (picks the LLM from the environment), `RuleBasedChatModel` / `RuleBasedStreamingChatModel` (deterministic placeholder LLMs that can call tools and stream), `LlmMetrics` (a `ChatModelListener`) |
| `memory` | `ConversationMemory` (memory for each `contextId`, with limits, idle expiry and LRU eviction) and `FileChatMemoryStore` (conversations stored as JSON files) |
| `agents` | `RemoteAgent`, the base class of all client agents: discovers the card once and keeps one `contextId` |

```
                         ┌──────────────── A2A (JSON-RPC over HTTP) ────────────────┐
OrderClientAgent ────────▶ order-agent      tools · system prompt · input-required
CatalogClientAgent ──────▶ catalog-agent    skills/routing · JSON data parts · structured output
ReportClientAgent ───────▶ report-agent     message/stream (SSE) · tasks/get · tasks/cancel
KnowledgeClientAgent ────▶ knowledge-agent  RAG over src/main/resources/knowledge/*.md
SupportClientAgent ──key─▶ support-agent    API key · guardrails · limits · memory · /metrics
CoordinatorClientAgent ──▶ coordinator-agent ──▶ OrderClientAgent ──▶ order-agent
                                            └──▶ KnowledgeClientAgent ──▶ knowledge-agent
```

## OrderAgent: tools, system prompt, input-required

- The `@SystemMessage` sets the agent's role and rules ("never invent order data", "ask when the order is
  missing").
- `OrderTools` has three `@Tool` methods: `getOrderStatus`, `listOrders` and `askForInput`. The tools read an
  in-memory `OrderRepository` (orders `#42`, `#7`, `#13`).
- `askForInput` uses `ReturnBehavior.IMMEDIATE`. When the LLM calls it, the AI service returns at once, and the
  agent ends the turn with `task.requireInput(question)`. The task state is then **`input-required`**.
- The client continues the **same task** by sending a message that carries that `taskId`. The server checks
  that the task is waiting for input, appends the message to the history and runs the agent again with the
  same chat memory.

```java
OrderClientAgent orders = new OrderClientAgent(new A2aClient(orderServerUri));
orders.ask("What is the status of order #42?").text();
orders.ask("Where is my order?", question -> "It is order 13");   // answers the clarifying question
orders.ask("Where is my order?");   // no answer available: returns needsInput=true and cancels the task
```

## CatalogAgent: skills, routing, structured data

- The card lists three skills, each with input and output modes: `product-lookup`, `product-search` and
  `summarize`.
- **Routing**: the agent uses the message metadata `{"skill": "..."}` when the client sets it. Otherwise it
  routes by content: a data part with `sku` or a SKU in the text goes to lookup, a "summarize…" prefix goes to
  summarize, and everything else goes to search. An unknown skill makes the task `rejected`.
- **Structured data**: lookup takes the data part `{"kind":"data","data":{"sku":"AUD-100"}}` and returns the
  product as a data part. Search has LangChain4j parse the free text into a `SearchQuery` record (structured
  output) and returns `{query, products}` as JSON.

```java
CatalogClientAgent catalog = new CatalogClientAgent(new A2aClient(catalogServerUri));
Product p = catalog.lookup("AUD-100");                          // typed record decoded from the data part
SearchResult r = catalog.search("audio products under $100");   // query={audio, 100.0}, products=[AUD-200]
```

## ReportAgent: streaming and the task lifecycle

- The card sets `capabilities.streaming=true`. `message/stream` responds with `text/event-stream`. The first
  event is a `task` snapshot, followed by `status-update` events (progress) and `artifact-update` events (the
  report, one chunk per token from a LangChain4j `TokenStream`). The stream ends with a status update marked
  `final: true`.
- With `message/send` and `configuration.blocking=false`, the server returns the task while it is still
  `submitted` or `working`. The client polls it with `tasks/get` and can stop it with `tasks/cancel`. The
  server interrupts the agent, which also checks `task.isCanceled()` between tokens. Canceling a task that has
  already finished returns error `-32002` (TaskNotCancelable).

```java
ReportClientAgent reports = new ReportClientAgent(new A2aClient(reportServerUri));
Report report = reports.stream("audio sales", System.out::print);   // prints the report as it is written
String id = reports.start("keyboard sales");                         // non-blocking
reports.status(id);   // working
reports.cancel(id);   // canceled
```

## KnowledgeAgent: RAG

- The documents in `src/main/resources/knowledge/` (returns, shipping, warranty) are split into segments by
  LangChain4j's `DocumentSplitters`.
- `KeywordContentRetriever` is a `ContentRetriever` that ranks segments by keyword overlap. It needs no
  embedding model, so the example stays offline and deterministic. Swap in an
  `EmbeddingStoreContentRetriever` for semantic search.
- The AI service adds the retrieved segments to the prompt. The system prompt tells the LLM to answer only
  from them.
- The reply is a text part plus a data part `{"sources": ["returns-policy.md"]}`, taken from `Result.sources()`.

## SupportAgent: hardening

| Concern | How |
|---|---|
| Authentication | The card declares `securitySchemes: {apiKey: {type: apiKey, in: header, name: X-API-Key}}`. `A2aServer` then requires a key from `Options.withApiKeys(...)` on every JSON-RPC call and on `/metrics`. A missing or wrong key gets **HTTP 401**. The agent card itself stays public. |
| Input guardrails | `Guardrails.MaxLength` and `Guardrails.PromptInjection` (LangChain4j `InputGuardrail`). The injection check works on normalized text: Unicode NFKC, invisible characters removed, whitespace and punctuation collapsed. A blocked request makes the task **`rejected`** with a short reason, and the LLM is never called. `Guardrails.CardNumberRedaction` masks card numbers in the request, so they never reach the LLM provider or the stored memory. |
| Output guardrail | `Guardrails.PiiRedaction` (`OutputGuardrail`) masks e-mail addresses and card numbers. |
| Limits | `A2aServer.Options`: `withMaxRequestBytes` (larger requests get HTTP 413), `withAgentTimeout` and `withMaxConcurrentTasks` (more running turns than that get HTTP 429, which caps LLM spend from non-blocking sends). A turn that runs over the timeout makes the task **`failed`** ("Timed out after …") and the agent thread is interrupted. The task store is bounded too (`maxTasks`). |
| Memory limits | `ConversationMemory` keeps up to N messages per conversation, M conversations (least recently used are evicted first) and drops conversations after an idle timeout. With `FileChatMemoryStore` an evicted conversation stays on disk and **resumes** when its context is used again. |
| Observability | `LlmMetrics` counts LLM calls, errors, tokens and latency. The server logs each task with its `taskId`/`contextId` (also put in the SLF4J MDC). `GET /metrics` returns request, task-state and agent counters as JSON. |

The `ConversationMemory` limits are used by every agent that keeps chat memory, including the original Agent2.

## CoordinatorAgent: delegation

The coordinator's tools (`DelegationTools`) call `OrderClientAgent` and `KnowledgeClientAgent`, so each tool
call is an A2A call to another agent. For "Where is order #42 and what is the return policy?" the LLM calls
both tools in one step and combines the answers. The reply's data part lists every delegation
(`{"delegations":[{"agent","question","answer"}]}`). If the order agent asks a clarifying question, the
coordinator passes that question on in its answer.

Every coordinator conversation gets its **own** conversation at the order and knowledge agents. The context id
is derived from the coordinator's memory key, so one caller's questions never leak into another caller's
answers. Answers from other agents are treated as untrusted: they are capped at 2,000 characters, and the
system prompt tells the LLM never to follow instructions found in them.

## A2A protocol layer

`A2aServer` (one agent per server):

| Endpoint / method | Behaviour |
|---|---|
| `GET /.well-known/agent-card.json` | Agent card; always public |
| `message/send` | Creates a task, or continues an `input-required` task named by `message.taskId`. Blocks until the task completes or needs input, unless `configuration.blocking=false`. |
| `message/stream` | Same, but replies with server-sent events. Only allowed when the card says `streaming: true`; otherwise returns `-32004`. |
| `tasks/get` | Task snapshot; `historyLength` limits the history. Unknown id: `-32001`. |
| `tasks/cancel` | Cancels a task that hasn't finished and interrupts the agent; a finished task gives `-32002` |
| `GET /metrics` | Counters as JSON |

Agents implement `A2aAgent`. A simple agent overrides `reply(message, contextId)`; text in, text out, and the
task completes. A richer agent overrides `execute(RequestContext, TaskUpdater)` and calls
`working(…)`, `artifact(…)`, `artifactChunk(…)`, `requireInput(…)`, `complete(…)`, `reject(…)` or `fail(…)`.
Each call updates the task (visible through `tasks/get`) and is published to stream subscribers. Every turn
runs on its own virtual thread.

## Security model

| Threat | Mitigation |
|---|---|
| A malicious agent card redirects the client (to steal the API key, or SSRF) | `A2aClient` only calls a card `url` on the same origin (scheme, host, port) it fetched the card from |
| A hostile server floods the client | Responses and streams are read up to 16 MB |
| One caller reads, continues or cancels another caller's task | Each task belongs to the principal that created it (`anonymous`, or an id derived from the API key). Other principals get "task not found". |
| One caller reuses another caller's `contextId` to read or poison their chat memory | Agents key memory by `RequestContext.memoryKey()`, which combines principal and `contextId` |
| Guessing or timing API keys | Keys are compared as SHA-256 digests in constant time, and every configured key is checked |
| Misconfiguration | A server whose card requires an API key refuses to start without keys |
| Cost or memory exhaustion | Request size limit, per-turn timeout, cap on concurrent tasks (HTTP 429), bounded task store and conversation memory; metrics count only known method names |
| Internal details leaking in errors | Agent exceptions become "Internal error (reference …)" and the details are logged; parse errors don't echo the input; guardrail rejections don't name internal classes |
| Hostile ids (`../`, very long) | `contextId` and `taskId` must be 1–128 characters of `[A-Za-z0-9._:-]` |
| Sensitive data at rest | `FileChatMemoryStore` creates an owner-only directory (`rwx------`) and owner-only files (`rw-------`), and names files by the SHA-256 of the memory id. Card numbers are redacted before storage. |

Known limits:
- The servers bind to `localhost` and speak plain HTTP; put them behind TLS before exposing them.
- With `anonymous` access, isolation relies on task and context ids being hard to guess. Let the server
  generate them, or require API keys.
- The order tools don't check which customer is asking. Any caller can look up any order.
- The output guardrail redacts the reply, but the chat memory stores the LLM's original output. This is why
  card numbers are redacted on input as well.
- The prompt-injection check is a heuristic, not a complete defence.

## Choosing the LLM

All feature agents get their model from `LlmConfig.fromEnv()`. By default they use deterministic
**placeholder** models (`RuleBasedChatModel`) that exercise the same tool, RAG, structured-output and
streaming code paths, so the build and the tests run offline.

| Variable | Values |
|---|---|
| `A2A_LLM` | `placeholder` (default), `ollama`, `anthropic` |
| `A2A_LLM_MODEL` | Model name. Default: `llama3.2` (ollama) or `claude-sonnet-5-5` (anthropic) |
| `A2A_LLM_BASE_URL` | Ollama URL (default `http://localhost:11434`); optional for anthropic |
| `ANTHROPIC_API_KEY` | Required for `anthropic` |
| `A2A_API_KEY` | API key for `support-agent` in the demo. If unset, the demo generates a random key for the run; there is no built-in default credential. |

```bash
A2A_LLM=anthropic ANTHROPIC_API_KEY=... ./mvnw compile exec:java -Dexec.mainClass=com.example.a2atest.A2aAgentsApp
```

Tests always pass `LlmConfig.placeholder()`, so they never call a real model.

## Run

```bash
./mvnw verify                                                                   # build + all tests (offline)
./mvnw compile exec:java -Dexec.mainClass=com.example.a2atest.A2aAgentsApp      # ports 10001-10006
```

Output with the placeholder LLM (abridged; the report streams in as it is written):

```
=== OrderAgent: tools, system prompt, input-required ===
Order #42 (noise-cancelling headphones) is shipped, expected on 2026-10-27.
  agent asks: Which order do you mean? Please send the order number.
  client answers: It is order 13
Order #13 (mechanical keyboard) is processing, expected on 2026-11-03.
alice has 2 orders: #42 noise-cancelling headphones (shipped), #7 USB-C cable (delivered).

=== CatalogAgent: skills, routing, structured data ===
lookup AUD-100 -> Product[sku=AUD-100, name=Noise-cancelling headphones, category=audio, price=199.0, inStock=true]
search -> query SearchQuery[category=audio, maxPrice=100.0, keyword=null], products [Product[sku=AUD-200, ...]]
routed by content -> Compact wireless keyboard: $59.0, in stock

=== ReportAgent: streaming, tasks/get, tasks/cancel ===
# Report: audio sales
...
non-blocking task e201a781-... is working
after tasks/cancel: canceled

=== KnowledgeAgent: RAG with sources ===
According to our policy: Opened headphones and earbuds can only be returned if they are defective, for hygiene reasons. [returns-policy.md]
I don't know; none of the policy documents cover that.

=== SupportAgent: API key, guardrails, memory, metrics ===
Support (message 1 of this conversation): thanks, I have logged "My headphones stopped working, reach me at [email redacted]" ...
Support (message 2 of this conversation): thanks, I have logged "They are from order 42" ...
rejected: Request blocked: Message looks like a prompt-injection attempt
wrong API key -> HTTP 401 from http://localhost:10005
metrics -> {"agent":"support-agent","requests":{...},"tasks":{"completed":2,"rejected":1},"agentMetrics":{"llm":{"llmCalls":2,...}}}

=== CoordinatorAgent: delegation to other agents ===
Order: Order #42 (noise-cancelling headphones) is shipped, expected on 2026-10-27.
Policy: According to our policy: Items can be returned within 30 days of delivery for a full refund. ... (sources: returns-policy.md)
  delegated to order-agent
  delegated to knowledge-agent
```

The demo stores the support agent's conversations in `target/support-memory/`, and `./mvnw clean` deletes
them.

| Test class | Covers |
|---|---|
| `A2aServerFeaturesTest` | Timeout, size limit, unknown task, streaming not supported, finished tasks, `/metrics` |
| `agents/OrderAgentTest` | Tools, `input-required` and continuing the same task, cancel when no answer is available |
| `agents/CatalogAgentTest` | Skills on the card, data parts both ways, structured output, routing, unknown skill |
| `agents/ReportAgentTest` | Streaming chunks and progress, artifact assembled on the server, non-blocking + `tasks/get` + `tasks/cancel`, polling |
| `agents/KnowledgeAgentTest` | Retrieval and sources, "don't know" when nothing is retrieved |
| `agents/SupportAgentTest` | 401 without a key, input guardrails, PII redaction, memory, `/metrics`, eviction (in memory and persistent) |
| `agents/CoordinatorAgentTest` | Fan-out to two agents, delegating only where needed, passing on a clarifying question |
| `llm/LlmConfigTest` | Reading the environment config (no model is contacted) |
| `SecurityTest` | One test per fix: card-url redirect, startup without keys, cross-caller task access, `contextId` reuse, coordinator isolation, id validation, 429, error hygiene, obfuscated injection, card data and file permissions at rest |
