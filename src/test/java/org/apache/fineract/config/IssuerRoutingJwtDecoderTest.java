package org.apache.fineract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.fineract.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.tenant.TenantServerConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * IssuerRoutingJwtDecoder with real signatures: one key stands in for the Keycloak
 * realm, another for jwt_pub.pem. The Keycloak side uses KeycloakTokens' own
 * validators, with the public key instead of a JWK set URL.
 */
class IssuerRoutingJwtDecoderTest {

    private static final String ISSUER = "https://keycloak.example.org/realms/paymenthub";

    private final KeyPair realmKey = rsaKeyPair();
    private final KeyPair legacyKey = rsaKeyPair();
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        AuthProperties.Keycloak keycloak = new AuthProperties.Keycloak();
        keycloak.setIssuerUri(ISSUER);
        NimbusJwtDecoder keycloakDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) realmKey.getPublic()).build();
        keycloakDecoder.setJwtValidator(KeycloakTokens.validators(keycloak));
        // legacy side reduced to the signature and timestamps; its validators have their own tests
        NimbusJwtDecoder legacyDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) legacyKey.getPublic()).build();
        legacyDecoder.setJwtValidator(JwtValidators.createDefault());
        decoder = new IssuerRoutingJwtDecoder(ISSUER, keycloakDecoder, legacyDecoder);

        TenantServerConnection tenant = new TenantServerConnection();
        tenant.setSchemaName("greenbank");
        ThreadLocalContextUtil.setTenant(tenant);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.clear();
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(KeyPair key, Map<String, Object> claims) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) key.getPublic()).privateKey((RSAPrivateKey) key.getPrivate()).build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().subject("mifos").issuedAt(now).expiresAt(now.plusSeconds(60));
        claims.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build()))
                .getTokenValue();
    }

    private static Map<String, Object> keycloakClaims(String issuer, List<String> tenants) {
        return Map.of("iss", issuer, "typ", "Bearer", "aud", "account", "tenants", tenants);
    }

    @Test
    void acceptsARealmTokenForAListedTenant() {
        String token = sign(realmKey, keycloakClaims(ISSUER, List.of("greenbank", "bluebank")));
        assertEquals(ISSUER, decoder.decode(token).getClaimAsString("iss"));
    }

    @Test
    void rejectsARealmTokenForAnotherTenant() {
        String token = sign(realmKey, keycloakClaims(ISSUER, List.of("bluebank")));
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void stillAcceptsALegacyTokenWithNoIssuer() {
        String token = sign(legacyKey, Map.of("authorities", List.of("ALL_FUNCTIONS")));
        assertEquals("mifos", decoder.decode(token).getSubject());
    }

    @Test
    void rejectsATokenClaimingTheRealmIssuerButSignedWithAnotherKey() {
        String token = sign(legacyKey, keycloakClaims(ISSUER, List.of("greenbank")));
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        // goes to the legacy decoder, whose key did not sign it
        String token = sign(realmKey, keycloakClaims("https://elsewhere.example.org/realms/paymenthub", List.of("greenbank")));
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsAMalformedToken() {
        assertThrows(JwtException.class, () -> decoder.decode("not-a-jwt"));
    }
}
