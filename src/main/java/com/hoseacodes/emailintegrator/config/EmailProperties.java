package com.hoseacodes.emailintegrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {
    
    private String defaultFromAddress;
    private String defaultFromName;
    private boolean enabled = true;
    
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
