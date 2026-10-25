package com.example.a2atest.agents;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.a2a.A2aServer;
import com.example.a2atest.agents.report.ReportAgent;
import com.example.a2atest.agents.report.ReportClientAgent;
import com.example.a2atest.llm.LlmConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportAgentTest {

    @Test
    void streamsProgressAndTheReportInChunks() throws Exception {
        try (A2aServer server = new A2aServer(0, new ReportAgent(LlmConfig.placeholder(), Duration.ZERO))) {
            ReportClientAgent reports = new ReportClientAgent(new A2aClient(server.baseUri()));
            assertTrue(reports.card().supportsStreaming());

            List<String> received = new ArrayList<>();
            ReportClientAgent.Report report = reports.stream("audio sales", received::add);

            assertEquals(A2a.TaskState.COMPLETED, report.state());
            assertEquals(List.of("Researching \"audio sales\"", "Writing the report"), report.progress());
            assertTrue(report.chunks() > 10, "chunks: " + report.chunks());
            assertTrue(report.text().startsWith("# Report: audio sales"), report.text());
            assertEquals(report.text(), String.join("", received));

            // the server assembled the same artifact from the chunks
            A2a.Task task = reports.status(report.taskId());
            assertEquals(report.text(), task.artifact(ReportAgent.ARTIFACT).text());
        }
    }

    @Test
    void nonBlockingTaskCanBePolledAndCanceled() throws Exception {
        try (A2aServer server = new A2aServer(0, new ReportAgent(LlmConfig.placeholder(), Duration.ofMillis(50)))) {
            ReportClientAgent reports = new ReportClientAgent(new A2aClient(server.baseUri()));

            String taskId = reports.start("keyboard sales");
            Thread.sleep(200);
            assertEquals(A2a.TaskState.WORKING, reports.status(taskId).status().state());

            assertEquals(A2a.TaskState.CANCELED, reports.cancel(taskId).status().state());
            Thread.sleep(200);
            A2a.Task task = reports.status(taskId);
            assertEquals(A2a.TaskState.CANCELED, task.status().state());

            A2aClient.A2aException again = assertThrows(A2aClient.A2aException.class, () -> reports.cancel(taskId));
            assertEquals(A2a.JsonRpcError.TASK_NOT_CANCELABLE, again.code());
        }
    }

    @Test
    void pollingWaitsForCompletion() throws Exception {
        try (A2aServer server = new A2aServer(0, new ReportAgent(LlmConfig.placeholder(), Duration.ZERO))) {
            ReportClientAgent reports = new ReportClientAgent(new A2aClient(server.baseUri()));
            A2a.Task task = reports.await(reports.start("cables"), Duration.ofMillis(20), Duration.ofSeconds(10));
            assertEquals(A2a.TaskState.COMPLETED, task.status().state());
            assertTrue(task.artifact(ReportAgent.ARTIFACT).text().contains("cables"));
        }
    }
}
