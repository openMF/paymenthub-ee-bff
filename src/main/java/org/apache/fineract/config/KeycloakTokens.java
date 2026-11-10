package org.apache.fineract.config;

import org.apache.fineract.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.tenant.TenantServerConnection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Access tokens issued by a Keycloak realm (rest.authorization.keycloak), as sent
 * by the operations web console. They differ from the /oauth/token ones in every
 * check ResourceServerConfig makes on those: signed with the realm's key, "aud" is
 * "account", no tenant schema in "aud" and no "authorities" claim. So they get
 * their own decoder and validators here:
 * - signature against the realm's JWK set, and "iss" must be the configured realm
 * - only access tokens ("typ" Bearer); an ID token is signed with the same key
 * - the request's tenant must be listed in the tenants claim, which replaces
 *   AudienceVerifier's audience check
 * - realm roles become the granted authorities, so rest.authorization.settings
 *   rules such as hasAuthority('ALL_FUNCTIONS') work unchanged
 */
final class KeycloakTokens {

    private KeycloakTokens() {
    }

    static JwtDecoder decoder(AuthProperties.Keycloak keycloak) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keycloak.resolvedJwkSetUri()).build();
        decoder.setJwtValidator(validators(keycloak));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validators(AuthProperties.Keycloak keycloak) {
        return new DelegatingOAuth2TokenValidator<>(List.of(JwtValidators.createDefaultWithIssuer(keycloak.getIssuerUri()),
                accessTokenOnlyValidator(), tenantValidator(keycloak.getTenantsClaim(), KeycloakTokens::currentTenantSchema)));
    }

    private static String currentTenantSchema() {
        TenantServerConnection tenant = ThreadLocalContextUtil.getTenant();
        return tenant == null ? null : tenant.getSchemaName();
    }

    static OAuth2TokenValidator<Jwt> accessTokenOnlyValidator() {
        return jwt -> {
            String type = jwt.getClaimAsString("typ");
            if ("Bearer".equals(type)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                    "Token type " + type + " cannot be used as an access token", null));
        };
    }

    /**
     * Same rule as AudienceVerifier, against the tenants claim instead of "aud". No
     * tenant in context fails too, for the same reason given there.
     */
    static OAuth2TokenValidator<Jwt> tenantValidator(String claim, Supplier<String> tenantSchema) {
        return jwt -> {
            String schema = tenantSchema.get();
            if (schema == null) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                        "No tenant in context, cannot verify the token tenants", null));
            }
            List<String> tenants = tenants(jwt, claim);
            if (tenants.contains(schema)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                    "Token tenants " + tenants + " do not include request tenant " + schema, null));
        };
    }

    /**
     * A Keycloak mapper writes a multivalued claim as a JSON array and a single value
     * as a string, so both are read; a string may also be comma-separated.
     */
    private static List<String> tenants(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        List<String> tenants = new ArrayList<>();
        if (value instanceof Collection<?> values) {
            values.forEach(v -> tenants.add(String.valueOf(v).trim()));
        } else if (value instanceof String text) {
            Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(tenants::add);
        }
        return tenants;
    }

    /** realm_access.roles, unprefixed, like the "authorities" claim of the /oauth/token tokens. */
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(String.valueOf(role))));
        }
        return authorities;
    }
}
