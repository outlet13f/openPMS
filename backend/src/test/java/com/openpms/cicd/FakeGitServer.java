package com.openpms.cicd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for GitLab or GitHub on 127.0.0.1 (random port). Register answers by path prefix (the longest prefix wins;
 * unknown paths answer 404 with {}); every request is recorded with lower-cased header names.
 */
public final class FakeGitServer implements AutoCloseable {
    public record Recorded(String path, String query, Map<String, String> headers) {}

    private record Answer(int status, String body) {}

    public record Reply(int status, String body) {}

    private final java.util.Map<String, java.util.function.Function<Recorded, Reply>> handlers = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeGitServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** body: a test resource when it starts with "cicd/" and ends with ".json", otherwise sent as it is. */
    public void respond(String pathPrefix, int status, String body) {
        String text = body;
        if (body.startsWith("cicd/") && body.endsWith(".json")) {
            try (var in = FakeGitServer.class.getClassLoader().getResourceAsStream(body)) {
                if (in == null) throw new IllegalArgumentException("no test resource " + body);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        answers.put(pathPrefix, new Answer(status, text));
    }

    /** An answer for one page of a list: matches the prefix and a query with page=N (wins over the plain prefix). */
    public void respondPage(String pathPrefix, int page, int status, String body) {
        respond(pathPrefix + "?page=" + page, status, body);
    }

    /** A computed answer for every request under the prefix (wins over respond() for the same or a shorter prefix). */
    public void respondWith(String pathPrefix, java.util.function.Function<Recorded, Reply> handler) {
        handlers.put(pathPrefix, handler);
    }

    public List<Recorded> requests() {
        return new ArrayList<>(requests);
    }

    @Override public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        var headers = new LinkedHashMap<String, String>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        var path = ex.getRequestURI().getPath();
        var recorded = new Recorded(path, ex.getRequestURI().getRawQuery(), Collections.unmodifiableMap(headers));
        requests.add(recorded);
        Answer best = new Answer(404, "{}");
        int bestLength = -1;
        var query = "&" + (ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery()) + "&";
        for (var e : answers.entrySet()) {
            var key = e.getKey();
            int q = key.indexOf("?page=");
            if (q >= 0) {
                if (!path.startsWith(key.substring(0, q)) || !query.contains("&page=" + key.substring(q + 6) + "&")) continue;
                best = e.getValue();
                bestLength = Integer.MAX_VALUE;
                break;
            }
            if (path.startsWith(e.getKey()) && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        String handlerPrefix = null;
        for (var key : handlers.keySet()) {
            if (path.startsWith(key) && (handlerPrefix == null || key.length() > handlerPrefix.length())) handlerPrefix = key;
        }
        if (handlerPrefix != null && handlerPrefix.length() >= bestLength) {
            var reply = handlers.get(handlerPrefix).apply(recorded);
            best = new Answer(reply.status(), reply.body());
        }
        var bytes = best.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(best.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }
}
