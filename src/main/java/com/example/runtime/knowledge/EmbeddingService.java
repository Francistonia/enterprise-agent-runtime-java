package com.example.runtime.knowledge;

import com.example.runtime.core.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {
    private final Environment env;
    private final OutboundHttp http;
    private final Secrets secrets;
    private final int dimensions;
    public EmbeddingService(Environment env, OutboundHttp http, Secrets secrets) {
        this.env = env; this.http = http; this.secrets = secrets;
        dimensions = env.getProperty("runtime.embedding.dimensions", Integer.class, 1024);
        if (dimensions < 16 || dimensions > 4096) throw new IllegalStateException("Invalid embedding dimensions");
        if ("fingerprint".equals(mode()) && !env.matchesProfiles("local", "test"))
            throw new IllegalStateException("Fingerprint embedding is only available in local/test profiles");
    }
    private String mode() { return env.getProperty("runtime.embedding.mode", "http"); }
    public String profile() { return mode() + "/" + env.getProperty("runtime.embedding.model", "") + "/" + dimensions; }
    public List<double[]> embed(List<String> input) {
        if (input.isEmpty()) return List.of();
        if ("fingerprint".equals(mode())) return input.stream().map(this::fingerprint).toList();
        String url = env.getProperty("runtime.embedding.url", "");
        if (url.isBlank()) throw Problem.unavailable("EMBEDDING_URL is required for semantic embeddings");
        String ref = env.getProperty("runtime.embedding.token-env", "RUNTIME_SECRET_EMBEDDING");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + secrets.get(ref));
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("model",env.getProperty("runtime.embedding.model","text-embedding-v3"));
        body.put("input",input);
        if(env.getProperty("runtime.embedding.request-dimensions",Boolean.class,true))body.put("dimensions",dimensions);
        var result = http.json("POST", url, headers, body);
        var items = result.path("data");
        if (!items.isArray() || items.size() != input.size()) throw Problem.unavailable("Invalid embedding batch size");
        List<double[]> vectors = new ArrayList<>(Collections.nCopies(input.size(), null));
        for (var item : items) {
            int index = item.path("index").asInt(-1);
            var values = item.path("embedding");
            if (index < 0 || index >= input.size() || vectors.get(index) != null || values.size() != dimensions)
                throw Problem.unavailable("Invalid embedding dimensions or index");
            double[] vector = new double[dimensions];
            for (int d = 0; d < dimensions; d++) {
                if (!values.get(d).isNumber()) throw Problem.unavailable("Invalid embedding value");
                vector[d] = values.get(d).asDouble();
            }
            validate(vector); vectors.set(index, vector);
        }
        return vectors;
    }
    private double[] fingerprint(String text) {
        double[] v = new double[dimensions];
        int[] chars = text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).toArray();
        for (int i = 0; i < chars.length; i++) {
            v[Math.floorMod(chars[i], dimensions)]++;
            if (i > 0) v[Math.floorMod(31 * chars[i - 1] + chars[i], dimensions)]++;
        }
        double norm = Math.sqrt(Arrays.stream(v).map(x -> x*x).sum());
        if (norm == 0) v[0] = 1; else for (int i = 0; i < dimensions; i++) v[i] /= norm;
        return v;
    }
    private void validate(double[] v) {
        if (Arrays.stream(v).anyMatch(x -> !Double.isFinite(x))
                || Arrays.stream(v).map(x -> x*x).sum() == 0) throw Problem.unavailable("Invalid embedding vector");
    }
}
