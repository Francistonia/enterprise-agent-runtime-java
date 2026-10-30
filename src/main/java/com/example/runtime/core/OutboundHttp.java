package com.example.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class OutboundHttp {
    private final Set<String> origins;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Json json;
    public OutboundHttp(Environment env, Json json) {
        this.json = json;
        origins = Arrays.stream(env.getProperty("runtime.allowed-origins", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> origin(URI.create(s)))
                .collect(Collectors.toUnmodifiableSet());
    }
    private static String origin(URI uri) {
        String scheme = uri.getScheme();
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null) throw Problem.bad("Invalid remote URI");
        int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }
    public URI check(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (Exception e) { throw Problem.bad("Invalid remote URI"); }
        if (!origins.contains(origin(uri))) throw Problem.bad("Remote origin is not in RUNTIME_ALLOWED_ORIGINS");
        return uri;
    }
    public byte[] bytes(String method, String url, Map<String, String> headers, byte[] body, String contentType, int limit) {
        HttpRequest.Builder request = HttpRequest.newBuilder(check(url)).timeout(Duration.ofSeconds(30));
        headers.forEach(request::header);
        if (contentType != null) request.header("Content-Type", contentType);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw Problem.unavailable("Remote request failed (HTTP " + response.statusCode() + ")");
                byte[] data = stream.readNBytes(limit + 1);
                if (data.length > limit) throw Problem.unavailable("Remote response exceeds size limit");
                return data;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw Problem.unavailable("Remote request interrupted");
        } catch (IOException e) { throw Problem.unavailable("Remote service is unavailable"); }
    }
    public JsonNode json(String method, String url, Map<String, String> headers, Object body) {
        byte[] request = body == null ? null : json.write(body).getBytes(StandardCharsets.UTF_8);
        return parse(bytes(method, url, headers, request, "application/json", 4 * 1024 * 1024));
    }
    public JsonNode parse(byte[] data) {
        try { return json.mapper().readTree(data); }
        catch (IOException e) { throw Problem.unavailable("Remote service returned invalid JSON"); }
    }
}
