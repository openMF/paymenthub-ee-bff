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

    public AuthProperties() {
    }

    public List<EndpointSetting> getSettings() {
        return settings;
    }
}
