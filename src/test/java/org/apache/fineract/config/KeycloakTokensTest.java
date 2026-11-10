package org.apache.fineract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The Keycloak-specific validators and the role mapping in KeycloakTokens. Pure
 * functions of a Jwt; the signature and issuer checks are covered by
 * IssuerRoutingJwtDecoderTest.
 */
class KeycloakTokensTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("mifos")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static boolean tenantOk(Object claimValue, String requestTenant) {
        Jwt token = claimValue == null ? jwt(Map.of()) : jwt(Map.of("tenants", claimValue));
        return !KeycloakTokens.tenantValidator("tenants", () -> requestTenant).validate(token).hasErrors();
    }

    @Test
    void accessTokenOnlyValidatorAcceptsABearerToken() {
        assertFalse(KeycloakTokens.accessTokenOnlyValidator().validate(jwt(Map.of("typ", "Bearer"))).hasErrors());
    }

    @Test
    void accessTokenOnlyValidatorRejectsAnIdToken() {
        // an ID token is signed with the same realm key, so the signature alone would let it through
        assertTrue(KeycloakTokens.accessTokenOnlyValidator().validate(jwt(Map.of("typ", "ID"))).hasErrors());
    }

    @Test
    void accessTokenOnlyValidatorRejectsATokenWithNoType() {
        assertTrue(KeycloakTokens.accessTokenOnlyValidator().validate(jwt(Map.of())).hasErrors());
    }

    @Test
    void tenantValidatorAcceptsAListedTenant() {
        assertTrue(tenantOk(List.of("greenbank", "bluebank"), "bluebank"));
    }

    @Test
    void tenantValidatorRejectsAnUnlistedTenant() {
        assertFalse(tenantOk(List.of("greenbank"), "redbank"));
    }

    @Test
    void tenantValidatorReadsASingleOrCommaSeparatedString() {
        assertTrue(tenantOk("greenbank", "greenbank"));
        assertTrue(tenantOk("greenbank, bluebank", "bluebank"));
        assertFalse(tenantOk("greenbank", "bluebank"));
    }

    @Test
    void tenantValidatorRejectsATokenWithoutTheClaim() {
        assertFalse(tenantOk(null, "greenbank"));
    }

    @Test
    void tenantValidatorRejectsWhenNoTenantIsInContext() {
        assertFalse(tenantOk(List.of("greenbank"), null));
    }

    @Test
    void realmRolesBecomeUnprefixedAuthorities() {
        Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("ALL_FUNCTIONS", "REFUND"))));
        List<String> names = KeycloakTokens.realmRoles(token).stream().map(GrantedAuthority::getAuthority).toList();
        assertEquals(List.of("ALL_FUNCTIONS", "REFUND"), names);
    }

    @Test
    void noRealmAccessMeansNoAuthorities() {
        assertTrue(KeycloakTokens.realmRoles(jwt(Map.of())).isEmpty());
    }
}
