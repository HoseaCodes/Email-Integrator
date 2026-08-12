package com.hoseacodes.emailintegrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {
    
    private String defaultFromAddress;
    private String defaultFromName;
    private boolean enabled = true;

    /**
     * Hosts that caller-supplied links in outgoing email may point at.
     *
     * <p>Empty means "any http(s) host", which is safe against anonymous abuse now that sending
     * requires authentication, but not against a leaked client key. Set this in any deployment
     * that matters. Enforced by {@code LinkSanitizer}.
     */
    private List<String> allowedLinkHosts = new ArrayList<>();

    public List<String> getAllowedLinkHosts() {
        return allowedLinkHosts;
    }

    public void setAllowedLinkHosts(List<String> allowedLinkHosts) {
        this.allowedLinkHosts = allowedLinkHosts;
    }

    public String getDefaultFromAddress() {
        return defaultFromAddress;
    }
    
    public void setDefaultFromAddress(String defaultFromAddress) {
        this.defaultFromAddress = defaultFromAddress;
    }
    
    public String getDefaultFromName() {
        return defaultFromName;
    }
    
    public void setDefaultFromName(String defaultFromName) {
        this.defaultFromName = defaultFromName;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
