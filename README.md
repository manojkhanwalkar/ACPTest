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
| `a2a/A2a` | A2A data model (AgentCard, Message, Part, Task, TaskStatus) and the JSON-RPC 2.0 envelope, camelCase JSON |
| `a2a/A2aServer` | A2A server on the JDK HTTP server: `GET /.well-known/agent-card.json`, JSON-RPC `message/send` at `POST /` |
| `a2a/A2aClient` | A2A client: fetches the agent card, then sends `message/send` to the card's `url` |
| `agent/Agent1` | Requesting agent: discovers Agent2 through its card and sends a message, using the A2A `contextId` as its conversation key |
| `agent/Agent2` | Responding agent: its answer becomes the agent message of a completed task |

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
