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
