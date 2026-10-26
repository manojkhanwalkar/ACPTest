# OWASP Top 10 (2025) Audit — ACPTest

_Date: 2026-10-25 · Scope: whole repository · Commit: b8ed91c · Tree: clean_

## System summary
A Java 26 / Maven sample of LangChain4j agents talking over two protocols. `com.example.acptest` hosts Agent2 on an
ACP REST server (`AcpServer`, JDK HttpServer on `localhost`: `/ping`, `/agents`, `/runs`) and Agent1 calls it
through `AcpClient`. `com.example.a2atest` is an A2A JSON-RPC server/client with six feature agents. Untrusted
input is the HTTP request body (JSON parsed by Jackson), the session/context ids, and responses read by the
clients. Assets: LLM provider keys (`ANTHROPIC_API_KEY` from env), the support-agent API key, chat memory (may hold
PII). The A2A layer was already hardened (auth, size/concurrency limits, id validation, error hygiene, client
origin checks); the ACP layer was not. Rated as sample code that integrators copy and run as documented.

## Summary
| Category | Status | Findings |
|---|---|---|
| A01 Broken Access Control | Issues | C:0 H:0 M:0 L:1 |
| A02 Security Misconfiguration | No issues found | C:0 H:0 M:0 L:0 |
| A03 Software Supply Chain Failures | Issues | C:0 H:1 M:0 L:0 |
| A04 Cryptographic Failures | No issues found | C:0 H:0 M:0 L:0 |
| A05 Injection | No issues found | C:0 H:0 M:0 L:0 |
| A06 Insecure Design | Issues | C:0 H:0 M:2 L:0 |
| A07 Authentication Failures | No issues found | C:0 H:0 M:0 L:0 |
| A08 Software or Data Integrity Failures | No issues found | C:0 H:0 M:0 L:0 |
| A09 Security Logging and Alerting Failures | No issues found | C:0 H:0 M:0 L:0 |
| A10 Mishandling of Exceptional Conditions | Issues | C:0 H:0 M:1 L:1 |

Six findings (High 1, Medium 3, Low 2), all in the dependency set or the ACP example. Fix first: the vulnerable
Jackson parser on the request path, the unbounded ACP request body and run concurrency, and the unbounded ACP
chat memory.

## Remediation
| Finding | Severity | Status | Notes |
|---|---|---|---|
| Vulnerable Jackson core/databind | High | Fixed | `jackson.version` 2.22.4 + `jackson-bom` import aligns all modules |
| ACP `/runs` has no size or concurrency limit | Medium | Fixed | 1 MiB cap (413), 64 concurrent runs (429) |
| ACP Agent2 chat memory unbounded | Medium | Fixed | LRU of 1,000 sessions, evicted via `ChatMemoryAccess` |
| ACP errors echo exception messages | Medium | Fixed | Generic messages with reference id; details logged |
| ACP session ids unvalidated / no caller isolation | Low | Partially fixed | Format validated; caller isolation deferred (needs auth design) |
| `AcpClient` reads unbounded responses | Low | Fixed | 16 MiB cap, like `A2aClient` |

**Verification:** baseline `./mvnw verify`: 56 tests, all pass. After fixes `./mvnw clean verify`: 61 tests, all
pass (new `AcpSecurityTest`: 413 on oversize body, parse errors don't echo input, bad session ids rejected, agent
exception text not returned, Agent2 memory bounded). `dependency:list` after the change shows jackson-core,
jackson-databind and jsr310 at 2.22.4; OSV query for those coordinates returns no advisories. Demo
`./mvnw compile exec:java` (ACP) still completes the Agent1/Agent2 exchange.
**Breaking changes for operators:** ACP clients sending bodies > 1 MiB, more than 64 concurrent runs, or
`session_id` values outside `[A-Za-z0-9._:-]{1,128}` are now refused. Error messages no longer include exception
text (look in the server log by reference id).
**Follow-ups:** decide an ACP authentication/caller-isolation model; consider a per-run timeout for ACP sync runs
(the A2A server has one).

## Findings

### A01 — Broken Access Control

#### [Low] ACP session ids unvalidated and not bound to a caller (Plausible)
**Status:** Partially fixed — `AcpServer` now validates `session_id` (1-128 chars of `[A-Za-z0-9._:-]`); caller isolation deferred.
- **Location:** `src/main/java/com/example/acptest/acp/AcpServer.java` (`createRun`), `agent/Agent2.java:run`
- **Evidence:**
  ```java
  String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
  ```
- **Scenario:** The ACP server has no authentication. Any local caller that knows or guesses another caller's
  `session_id` reads and appends to that conversation's chat memory; arbitrary-length ids also inflate memory keys.
  Exploitability depends on whether ids are guessable and whether the server is exposed beyond localhost.
- **Fix:** Validate the id format (done). Deferred: add authentication and key memory by principal + session id
  (as the A2A layer does with `RequestContext.memoryKey()`); this changes the ACP example's trust model.

### A02 — Security Misconfiguration
No issues. Servers bind to `localhost`; no debug endpoints; A2A refuses to start without keys when the card requires
them; `X-Content-Type-Options: nosniff` now also set on ACP responses.

### A03 — Software Supply Chain Failures

#### [High] Known-vulnerable Jackson parser on the request path (Confirmed)
**Status:** Fixed — `jackson.version` 2.21.1 → 2.22.4 and `jackson-bom` imported first in `dependencyManagement`.
- **Location:** `pom.xml` (`jackson.version`); resolved jackson-databind 2.21.1, jackson-core 2.22.1 (via LangChain4j)
- **Evidence:** OSV lists 16 advisories for jackson-databind 2.21.1 (e.g. GHSA-wv8q-qhhj-9h54 unbounded retention of
  unknown type ids, GHSA-3pjw-73gf-8qr5, GHSA-5gvw-p9qm-jgwh) and 2 for jackson-core 2.22.1 (GHSA-p6pp-m3f8-5c89
  ReDoS in number parsing, GHSA-7hhh-6rmp-j9qf).
- **Scenario:** Both servers parse untrusted request bodies with Jackson; a crafted body can trigger the parser's
  DoS bugs. The versions were also mismatched (core 2.22 vs databind 2.21).
- **Fix:** Use a patched, aligned Jackson via the BOM.

### A04 — Cryptographic Failures
No issues. API keys compared as SHA-256 digests in constant time; ids from `UUID.randomUUID()`; no custom crypto.
Plain HTTP on localhost is documented as a known limit.

### A05 — Injection
No issues. No SQL, shell, template or file-path sinks from request data (`FileChatMemoryStore` hashes ids into file
names). Prompt injection is mitigated heuristically in the support agent and documented as such.

### A06 — Insecure Design

#### [Medium] ACP `/runs` has no request-size or concurrency limit (Confirmed)
**Status:** Fixed — body read with `readNBytes(MAX_REQUEST_BYTES + 1)` (1 MiB, HTTP 413); `Semaphore` of 64 running runs (HTTP 429).
- **Location:** `src/main/java/com/example/acptest/acp/AcpServer.java` (`createRun`)
- **Evidence:**
  ```java
  request = Acp.JSON.readValue(ex.getRequestBody(), Acp.RunCreateRequest.class);
  ```
- **Scenario:** A caller posts a multi-GB body or thousands of parallel runs (each on its own virtual thread and,
  with a real LLM, a paid model call) → memory exhaustion or unbounded LLM spend.
- **Fix:** As applied, mirroring `A2aServer.Options`.

#### [Medium] ACP Agent2 keeps a chat memory per session forever (Confirmed)
**Status:** Fixed — Agent2 tracks sessions in LRU order (max 1,000) and calls `assistant.evictChatMemory` for the oldest; `Assistant` extends `ChatMemoryAccess`.
- **Location:** `src/main/java/com/example/acptest/agent/Agent2.java` (constructor)
- **Evidence:**
  ```java
  .chatMemoryProvider(sessionId -> MessageWindowChatMemory.withMaxMessages(20))
  ```
- **Scenario:** Each new `session_id` creates a memory that LangChain4j's AI service caches indefinitely; a caller
  looping over fresh ids grows the heap until OOM.
- **Fix:** As applied (the A2A Agent2 already uses `ConversationMemory`).

### A07 — Authentication Failures
No issues in the A2A layer (key auth, 401, constant-time compare, no default credential). The ACP example has no
authentication by design; see the A01 finding.

### A08 — Software or Data Integrity Failures
No issues. Jackson default typing is not enabled; LangChain4j chat-memory JSON is read only from the owner-only
store directory. Maven wrapper pins its distribution URL.

### A09 — Security Logging and Alerting Failures
No issues. A2A logs each task with ids and rejections are counted in `/metrics`; ACP failures are now logged with a
reference id. No secrets logged.

### A10 — Mishandling of Exceptional Conditions

#### [Medium] ACP errors return internal exception messages (Confirmed)
**Status:** Fixed — parse errors return "Malformed run request"; agent and unhandled exceptions return "Internal error (reference xxxxxxxx)" with details logged; 404/405 no longer echo the path or method.
- **Location:** `src/main/java/com/example/acptest/acp/AcpServer.java` (`createRun`, `handle`)
- **Evidence:**
  ```java
  sendJson(ex, 400, new Acp.Error("invalid_input", "Malformed run request: " + e.getMessage()));
  new Acp.Error("server_error", e.getMessage())
  ```
- **Scenario:** Jackson messages quote the input and source location; agent exceptions from a real LLM provider can
  carry URLs, model/config details or provider error bodies, all returned to any caller.
- **Fix:** As applied (same pattern as `A2aServer`).

#### [Low] `AcpClient` buffers unbounded responses (Confirmed)
**Status:** Fixed — responses read up to 16 MiB, larger ones fail with `AcpException`.
- **Location:** `src/main/java/com/example/acptest/acp/AcpClient.java` (`send`)
- **Evidence:** `HttpResponse.BodyHandlers.ofByteArray()`
- **Scenario:** A hostile or compromised ACP server streams an endless body → client OOM.
- **Fix:** As applied (same cap as `A2aClient`).

## Out of scope / not checked
- Deployment, TLS termination and network exposure (servers bind to localhost).
- Dependency scan: no OWASP dependency-check/osv-scanner/trivy installed; resolved runtime/compile dependencies
  (`mvnw dependency:list`) were queried against the OSV API (api.osv.dev querybatch). Test-scope and Maven plugin
  dependencies were not scanned.
- Real LLM providers (Ollama/Anthropic) were not exercised; tests use the placeholder models.
