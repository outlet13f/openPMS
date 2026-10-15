package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CicdHttpTest {
    HttpServer server;
    String base;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write('x');
                out.flush();
                Thread.sleep(3000);
                out.write('y');
            } catch (Exception ignored) { }
        });
        server.createContext("/big", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                var chunk = new byte[64 * 1024];
                for (int i = 0; i < 48; i++) out.write(chunk); // 3 MiB
            } catch (Exception ignored) { }
        });
        server.createContext("/ok", ex -> {
            var b = "hello".getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (var out = ex.getResponseBody()) { out.write(b); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void readsASmallBody() throws Exception {
        var r = new CicdHttp().get(base + "/ok", Map.of());
        assertThat(r.status()).isEqualTo(200);
        assertThat(new String(r.body())).isEqualTo("hello");
    }

    @Test void aBodyThatTricklesIsCutOffAtTheDeadline() {
        var http = new CicdHttp(Duration.ofMillis(500));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> http.get(base + "/slow?token=secret", Map.of()))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("secret").hasMessageNotContaining("127.0.0.1");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void aBodyOverTwoMiBIsRefused() {
        assertThatThrownBy(() -> new CicdHttp().get(base + "/big", Map.of()))
                .isInstanceOf(IOException.class).hasMessage("response too large");
    }
}
