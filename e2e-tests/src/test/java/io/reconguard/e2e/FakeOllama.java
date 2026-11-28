package io.reconguard.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for Ollama's /api/embed so the AI worker runs unmodified with deterministic vectors.
 * Text containing SCENARIO_nn embeds at cosine nn/100 against e0; any other text embeds to a one-hot
 * axis in 1..766 (orthogonal to e0). The test pins one known issue to e0, so SCENARIO_95 matches it at 0.95.
 */
final class FakeOllama implements AutoCloseable {

    static final int DIM = 768;
    private static final int RESERVED_AXIS = DIM - 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;

    FakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            JsonNode input = body.get("input");
            List<float[]> vectors = new ArrayList<>();
            if (input.isArray()) {
                input.forEach(t -> vectors.add(embed(t.asText())));
            } else {
                vectors.add(embed(input.asText()));
            }
            var out = JSON.createObjectNode();
            out.put("model", "nomic-embed-text");
            var arr = out.putArray("embeddings");
            for (float[] v : vectors) {
                var row = arr.addArray();
                for (float f : v) {
                    row.add(f);
                }
            }
            out.put("total_duration", 1);
            out.put("load_duration", 1);
            out.put("prompt_eval_count", 1);
            byte[] bytes = JSON.writeValueAsBytes(out);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // Anything else (e.g. probes) answers 200 with an empty object
        server.createContext("/", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    static float[] embed(String text) {
        float[] v = new float[DIM];
        int at = text.indexOf("SCENARIO_");
        if (at >= 0) {
            double cos = Integer.parseInt(text.substring(at + 9, at + 11)) / 100.0;
            v[0] = (float) cos;
            v[RESERVED_AXIS] = (float) Math.sqrt(1 - cos * cos);
        } else {
            v[1 + Math.floorMod(text.hashCode(), RESERVED_AXIS - 1)] = 1f;
        }
        return v;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
