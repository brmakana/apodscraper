package com.hexghost.apod;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

final class TestSupport {
    static ApodProperties config(Path root, String... overrides) {
        Map<String, Object> values = new HashMap<>();
        values.put("apod.download-directory", root.resolve("images").toString());
        values.put("apod.state-directory", root.resolve("state").toString());
        values.put("apod.request-interval", "0ms");
        values.put("apod.rate-limit-per-second", "1000");
        values.put("apod.retry-backoff", "1ms");
        values.put("apod.request-timeout", "2s");
        values.put("apod.shutdown-grace-period", "3s");
        for (int i = 0; i < overrides.length; i += 2) values.put("apod." + overrides[i], overrides[i + 1]);
        return new Binder(new MapConfigurationPropertySource(values)).bind("apod", Bindable.of(ApodProperties.class)).get();
    }
    static class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.start();
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        void handle(String path, HttpHandler handler) {
            server.createContext(path, exchange -> { requests.add(exchange.getRequestURI().getPath());
                try { handler.handle(exchange); } finally { exchange.close(); } });
        }
        void text(String path, String text) { handle(path, ex -> reply(ex, 200, "text/html", text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        void image(String path, String content) { handle(path, ex -> reply(ex, 200, "image/jpeg", content.getBytes())); }
        static void reply(HttpExchange ex, int code, String type, byte[] data) throws IOException {
            ex.getResponseHeaders().set("Content-Type", type); ex.sendResponseHeaders(code, data.length); ex.getResponseBody().write(data);
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
    static String page(String date, String media, String previous) {
        return "<table class='media-detail-hero__meta-table'><tr><th>Date:</th><td>" + date + "</td></tr></table>"
                + "<div class='extra media-detail-hero__media'>" + media + "</div>"
                + (previous == null ? "" : "<a class='extra smd-next-prev__prev' href='" + previous + "'>Previous</a>");
    }
    static String image(String path) { return "<a href='" + path + "'><img src='thumb.jpg'></a>"; }
}
