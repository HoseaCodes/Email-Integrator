package com.hoseacodes.emailintegrator.model;

public class UserData {
    private String email;
    private String name;
    private String appName;
    private String appDisplayName;
    private String approvalUrl;
    private String denyUrl;
    private String loginUrl;
    private String resetUrl;
    private String expiryTime;
    
    public UserData() {
    }
    
    public UserData(String email, String name) {
        this.email = email;
        this.name = name;
    }
    
    public UserData(String email, String name, String appName, String appDisplayName) {
        this.email = email;
        this.name = name;
        this.appName = appName;
        this.appDisplayName = appDisplayName;
    }
    
    public UserData(String email, String name, String appName, String appDisplayName, String approvalUrl, String denyUrl) {
        this.email = email;
        this.name = name;
        this.appName = appName;
        this.appDisplayName = appDisplayName;
        this.approvalUrl = approvalUrl;
        this.denyUrl = denyUrl;
    }
    
    public UserData(String email, String name, String appName, String appDisplayName, String approvalUrl, String denyUrl, String loginUrl) {
        this.email = email;
        this.name = name;
        this.appName = appName;
        this.appDisplayName = appDisplayName;
        this.approvalUrl = approvalUrl;
        this.denyUrl = denyUrl;
        this.loginUrl = loginUrl;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getAppName() {
        return appName;
    }
    
    public void setAppName(String appName) {
        this.appName = appName;
    }
    
    public String getAppDisplayName() {
        return appDisplayName;
    }
    
    public void setAppDisplayName(String appDisplayName) {
        this.appDisplayName = appDisplayName;
    }
    
    public String getApprovalUrl() {
        return approvalUrl;
    }
    
    public void setApprovalUrl(String approvalUrl) {
        this.approvalUrl = approvalUrl;
    }
    
    public String getDenyUrl() {
        return denyUrl;
    }
    
    public void setDenyUrl(String denyUrl) {
        this.denyUrl = denyUrl;
    }
    
    public String getLoginUrl() {
        return loginUrl;
    }
    
    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }
    
    public String getResetUrl() {
        return resetUrl;
    }
    
    public void setResetUrl(String resetUrl) {
        this.resetUrl = resetUrl;
    }
    
    public String getExpiryTime() {
        return expiryTime;
    }
    
    public void setExpiryTime(String expiryTime) {
        this.expiryTime = expiryTime;
    }
}
