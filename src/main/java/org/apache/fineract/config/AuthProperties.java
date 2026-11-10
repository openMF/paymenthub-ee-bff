package org.apache.fineract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Named: paymenthub-ee-core's org.mifos.connector.common.camel.AuthProperties is also a
// component called "authProperties", registered when rest.authorization.enabled is true,
// and the clash stopped the application from starting with authorization on.
@Component("restAuthProperties")
@ConfigurationProperties(prefix = "rest.authorization")
public class AuthProperties {

    private List<EndpointSetting> settings = new ArrayList<>();

    private final Keycloak keycloak = new Keycloak();

    public AuthProperties() {
    }

    public List<EndpointSetting> getSettings() {
        return settings;
    }

    public Keycloak getKeycloak() {
        return keycloak;
    }

    /**
     * Keycloak realm whose access tokens are accepted next to the ones issued by
     * /oauth/token. Off while issuer-uri is empty.
     *
     * issuer-uri and jwk-set-uri are separate because the browser and the BFF reach
     * Keycloak by different names: tokens carry the public issuer, while inside the
     * cluster the BFF fetches the keys from the Keycloak service.
     */
    public static class Keycloak {

        /** Must equal the "iss" claim of the realm's tokens, e.g. https://keycloak.example.org/realms/paymenthub */
        private String issuerUri = "";

        /** Where the realm's signing keys are fetched; defaults to the issuer's own certs endpoint. */
        private String jwkSetUri = "";

        /** Claim listing the tenant schema names the user may act for. */
        private String tenantsClaim = "tenants";

        public boolean isEnabled() {
            return issuerUri != null && !issuerUri.isBlank();
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String resolvedJwkSetUri() {
            if (jwkSetUri != null && !jwkSetUri.isBlank()) {
                return jwkSetUri;
            }
            return issuerUri + "/protocol/openid-connect/certs";
        }

        public String getTenantsClaim() {
            return tenantsClaim;
        }

        public void setTenantsClaim(String tenantsClaim) {
            this.tenantsClaim = tenantsClaim;
        }
    }
}
