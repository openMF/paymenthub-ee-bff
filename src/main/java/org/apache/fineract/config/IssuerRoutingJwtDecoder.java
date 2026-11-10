package org.apache.fineract.config;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.text.ParseException;

/**
 * Sends a bearer token to the Keycloak decoder when its "iss" is the configured
 * realm, and to the /oauth/token decoder otherwise (those tokens carry no "iss").
 *
 * The issuer is read before the signature is checked, but only to pick a decoder:
 * each one verifies the signature against its own keys, and the Keycloak one checks
 * "iss" again, so a forged "iss" just fails in the decoder it picked.
 */
class IssuerRoutingJwtDecoder implements JwtDecoder {

    private final String keycloakIssuer;
    private final JwtDecoder keycloakDecoder;
    private final JwtDecoder legacyDecoder;

    IssuerRoutingJwtDecoder(String keycloakIssuer, JwtDecoder keycloakDecoder, JwtDecoder legacyDecoder) {
        this.keycloakIssuer = keycloakIssuer;
        this.keycloakDecoder = keycloakDecoder;
        this.legacyDecoder = legacyDecoder;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("Malformed token: " + e.getMessage(), e);
        }
        return keycloakIssuer.equals(issuer) ? keycloakDecoder.decode(token) : legacyDecoder.decode(token);
    }
}
