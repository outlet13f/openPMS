package com.openpms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Behind the company's reverse proxy every request reaches the app from the proxy. The sign-in limit still counts
 * each person on their own: the client address comes from X-Forwarded-For when a trusted proxy (here, this host) sends it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"openpms.jobs.enabled=false", "openpms.logins-per-minute=3"})
@ActiveProfiles("dev")
class ClientAddressIT {
    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    String xsrf;

    @BeforeEach
    void csrfToken() throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/password-policy")).build(), HttpResponse.BodyHandlers.ofString());
        xsrf = r.headers().allValues("set-cookie").stream().map(Pattern.compile("XSRF-TOKEN=([^;]+)")::matcher).filter(Matcher::find).findFirst()
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie")).group(1);
    }

    int login(String forwardedFor) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("Cookie", "XSRF-TOKEN=" + xsrf).header("X-XSRF-TOKEN", xsrf)
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@openpms.local\",\"password\":\"x\"}")).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void eachPersonBehindTheProxyHasTheirOwnSignInLimit() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(login("203.0.113.7")).isEqualTo(401);
        assertThat(login("203.0.113.7")).isEqualTo(429);
        // someone else through the same proxy is still checked, not turned away
        assertThat(login("203.0.113.8")).isEqualTo(401);
        // an address the person made up in front of the proxy's entry does not reset their count
        assertThat(login("198.51.100.1, 203.0.113.7")).isEqualTo(429);
    }
}
