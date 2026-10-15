package com.openpms.cicd;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Outbound GET for the code-host adapters: 5 s to connect, 10 s for the answer, no redirects followed, at most 2 MiB of body
 * read. Error messages never carry the URL (its query) or a header value (the token).
 */
@Component
public class CicdHttp {
    public static final int MAX_BODY = 2 * 1024 * 1024;

    public record HttpResult(int status, byte[] body) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpResult get(String url, Map<String, String> headers) throws IOException {
        HttpRequest request;
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
                throw new IOException("invalid url");
            }
            var b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
            headers.forEach(b::header);
            request = b.build();
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid url");
        }
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var in = response.body()) {
                var bytes = in.readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) throw new IOException("response too large");
                return new HttpResult(response.statusCode(), bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException e) {
            if ("response too large".equals(e.getMessage())) throw e;
            throw new IOException("request failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
