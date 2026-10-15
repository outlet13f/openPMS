package com.openpms.cicd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Outbound GET for the code-host adapters: 5 s to connect, one 10 s deadline for the whole answer (headers and body), no
 * redirects followed, at most 2 MiB of body read. Error messages never carry the URL (its query) or a header value (the token).
 */
@Component
public class CicdHttp {
    public static final int MAX_BODY = 2 * 1024 * 1024;

    public record HttpResult(int status, byte[] body) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Duration deadline;

    public CicdHttp() { this(Duration.ofSeconds(10)); }

    /** For tests: a shorter total deadline. */
    CicdHttp(Duration deadline) { this.deadline = deadline; }

    public HttpResult get(String url, Map<String, String> headers) throws IOException {
        HttpRequest request;
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
                throw new IOException("invalid url");
            }
            var b = HttpRequest.newBuilder(uri).timeout(deadline).GET();
            headers.forEach(b::header);
            request = b.build();
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid url");
        }
        var future = client.sendAsync(request, info -> new Limited());
        try {
            var response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            return new HttpResult(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("request timed out");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException io && "response too large".equals(io.getMessage())) throw io;
            if (cause instanceof java.net.http.HttpTimeoutException) throw new IOException("request timed out");
            throw new IOException("request failed (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")");
        }
    }

    /** Collects the body, and gives up as soon as it passes MAX_BODY. */
    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (var bb : items) {
                if (buf.size() + bb.remaining() > MAX_BODY) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("response too large"));
                    return;
                }
                var chunk = new byte[bb.remaining()];
                bb.get(chunk);
                buf.writeBytes(chunk);
            }
        }

        @Override public void onError(Throwable t) { result.completeExceptionally(t); }

        @Override public void onComplete() { result.complete(buf.toByteArray()); }

        @Override public CompletionStage<byte[]> getBody() { return result; }
    }
}
