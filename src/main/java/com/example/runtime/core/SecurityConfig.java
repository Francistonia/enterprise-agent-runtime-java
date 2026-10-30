package com.example.runtime.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private HttpSecurity base(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.requestMatchers("/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated());
    }
    @Bean @Profile("local")
    SecurityFilterChain local(HttpSecurity http) throws Exception {
        return base(http).httpBasic(Customizer.withDefaults()).build();
    }
    @Bean @Profile("local")
    UserDetailsService localUsers(Environment env) {
        String admin = env.getProperty("LOCAL_ADMIN_PASSWORD", "local-admin");
        String user = env.getProperty("LOCAL_USER_PASSWORD", "local-user");
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password("{noop}" + admin).authorities("SCOPE_*").build(),
                User.withUsername("alice").password("{noop}" + user).authorities(
                        "SCOPE_agent:run", "SCOPE_kb:read", "SCOPE_a2a:invoke", "SCOPE_sandbox:execute", "SCOPE_mcp:invoke").build(),
                User.withUsername("bob").password("{noop}" + user).authorities(
                        "SCOPE_agent:run", "SCOPE_kb:read", "SCOPE_a2a:invoke", "SCOPE_mcp:invoke").build());
    }
    @Bean @Profile("!local")
    SecurityFilterChain production(HttpSecurity http, Environment env) throws Exception {
        String issuer = env.getRequiredProperty("runtime.oidc.issuer");
        if (issuer.isBlank()) throw new IllegalStateException("OIDC_ISSUER is required outside the local profile");
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        String audience = env.getRequiredProperty("runtime.oidc.audience");
        decoder.setJwtValidator(JwtPolicy.validator(issuer,audience));
        return base(http).oauth2ResourceServer(c -> c.jwt(j -> j.decoder(decoder))).build();
    }
}
