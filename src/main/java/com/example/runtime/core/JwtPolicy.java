package com.example.runtime.core;

import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

public final class JwtPolicy {
    private JwtPolicy() {}
    public static OAuth2TokenValidator<Jwt> validator(String issuer,String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> {
                    String tenant=jwt.getClaimAsString("tenant_id"),subject=jwt.getSubject();
                    return jwt.getAudience().contains(audience) && tenant!=null && !tenant.isBlank()
                            && tenant.length()<=128 && subject!=null && !subject.isBlank() && subject.length()<=256
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                });
    }
}
