package com.example.runtime.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OutboundAuth {
    public enum Mode { NONE, BEARER, BASIC, CLIENT_CREDENTIALS, TOKEN_EXCHANGE }
    public record Credential(Mode mode, String tokenRef, String tokenUrl, String clientId,
                             String clientSecretRef, String audience, String scope) {}
    private final OutboundHttp http;
    private final Secrets secrets;
    public OutboundAuth(OutboundHttp http, Secrets secrets) { this.http = http; this.secrets = secrets; }
    public Map<String, String> headers(Actor actor, Credential credential) {
        if (credential == null || credential.mode() == null || credential.mode() == Mode.NONE) return Map.of();
        if (credential.mode() == Mode.BASIC)
            return Map.of("Authorization","Basic "+java.util.Base64.getEncoder().encodeToString(
                    secrets.get(credential.tokenRef()).getBytes(StandardCharsets.UTF_8)));
        String token;
        if (credential.mode() == Mode.BEARER) token = secrets.get(credential.tokenRef());
        else {
            var form = new LinkedHashMap<String, String>();
            form.put("client_id", credential.clientId());
            form.put("client_secret", secrets.get(credential.clientSecretRef()));
            if (credential.scope() != null) form.put("scope", credential.scope());
            if (credential.audience() != null) form.put("audience", credential.audience());
            if (credential.mode() == Mode.TOKEN_EXCHANGE) {
                if (actor.bearer() == null) throw Problem.forbidden();
                form.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
                form.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
                form.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
                form.put("subject_token", actor.bearer());
            } else form.put("grant_type", "client_credentials");
            String encoded = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            var response = http.parse(http.bytes("POST", credential.tokenUrl(), Map.of(),
                    encoded.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", 65536));
            token = response.path("access_token").asText();
            if (token.isBlank() || !"Bearer".equalsIgnoreCase(response.path("token_type").asText("Bearer")))
                throw Problem.unavailable("Authorization server did not return a bearer token");
        }
        return Map.of("Authorization", "Bearer " + token);
    }
    public void validate(Credential credential) {
        if (credential != null && credential.mode() != null
                && credential.mode() != Mode.NONE && credential.mode() != Mode.BEARER && credential.mode() != Mode.BASIC) {
            http.check(credential.tokenUrl());
            if (credential.clientId() == null || credential.clientId().isBlank())
                throw Problem.bad("OAuth clientId is required");
        }
    }
    private String encode(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
