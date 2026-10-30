package com.example.runtime.core;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public record Actor(String tenant, String subject, Set<String> scopes, Set<String> groups, String bearer) {
    public Actor {
        scopes = Set.copyOf(scopes);
        groups = Set.copyOf(groups);
        if (tenant == null || tenant.isBlank() || tenant.length() > 128
                || subject == null || subject.isBlank() || subject.length() > 256) throw Problem.forbidden();
    }
    public boolean can(String scope) { return scopes.contains("*") || scopes.contains(scope); }
    public void require(String scope) { if (!can(scope)) throw Problem.forbidden(); }
    public boolean matches(Set<String> readers) {
        return readers != null && (readers.contains("*") || readers.contains("user:" + subject)
                || groups.stream().anyMatch(g -> readers.contains("group:" + g)));
    }
    public static Actor from(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw Problem.forbidden();
        Set<String> scopes = auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("SCOPE_")).map(a -> a.substring(6)).collect(Collectors.toSet());
        if (auth instanceof JwtAuthenticationToken token) {
            var groupList = token.getToken().getClaimAsStringList("groups");
            return new Actor(token.getToken().getClaimAsString("tenant_id"), token.getToken().getSubject(),
                    scopes, groupList == null ? Set.of() : Set.copyOf(groupList), token.getToken().getTokenValue());
        }
        return new Actor("local", auth.getName(), scopes, Set.of(), null);
    }
}
