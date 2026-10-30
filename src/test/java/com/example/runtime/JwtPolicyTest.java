package com.example.runtime;

import com.example.runtime.core.JwtPolicy;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class JwtPolicyTest {
    static KeyPair keys;
    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
    }
    private NimbusJwtDecoder decoder() {
        var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)keys.getPublic()).build();
        decoder.setJwtValidator(JwtPolicy.validator("https://issuer.example","agent-runtime"));return decoder;
    }
    private String token(String issuer,String audience,String tenant,Instant expires,KeyPair signing) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("alice").audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expires))
                .claim("scope","agent:run kb:read");
        if(tenant!=null)claims.claim("tenant_id",tenant);
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey)signing.getPrivate()));return jwt.serialize();
    }
    @Test void acceptsAValidSignedTenantToken() throws Exception {
        assertThat(decoder().decode(token("https://issuer.example","agent-runtime","tenant",Instant.now().plusSeconds(60),keys))
                .getSubject()).isEqualTo("alice");
    }
    @Test void rejectsAnotherAudience() throws Exception {
        String token=token("https://issuer.example","another-service","tenant",Instant.now().plusSeconds(60),keys);
        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void rejectsAnotherIssuer() throws Exception {
        String token=token("https://other.example","agent-runtime","tenant",Instant.now().plusSeconds(60),keys);
        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void rejectsExpiredTokens() throws Exception {
        String token=token("https://issuer.example","agent-runtime","tenant",Instant.now().minusSeconds(180),keys);
        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void rejectsMissingTenant() throws Exception {
        String token=token("https://issuer.example","agent-runtime",null,Instant.now().plusSeconds(60),keys);
        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void rejectsWrongSignature() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        String token=token("https://issuer.example","agent-runtime","tenant",Instant.now().plusSeconds(60),generator.generateKeyPair());
        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }
}
