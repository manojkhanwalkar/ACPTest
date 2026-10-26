package com.example.acptest.acp;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client's timeout covers reading the response body, not only the headers. */
class AcpClientTimeoutTest {

    @Test
    void stalledResponseBodyTimesOut() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread stalling = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[8192]);
                    OutputStream out = socket.getOutputStream();
                    // headers promise a body that never comes
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(30_000);
                } catch (Exception ignored) {
                    // the client gave up
                }
            });
            AcpClient client = new AcpClient(URI.create("http://127.0.0.1:" + server.getLocalPort()),
                    Duration.ofSeconds(2));
            AcpClient.AcpException e = assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> assertThrows(AcpClient.AcpException.class, client::agents));
            assertTrue(e.getMessage().contains("timed out"), e.getMessage());
            stalling.interrupt();
        }
    }
}
