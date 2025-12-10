# Using Vault for Gmail Credentials

Since you already have Vault configured, you can store your Gmail credentials securely in Vault.

## Store Credentials in Vault

```bash
# Store Gmail credentials in Vault
vault kv put secret/email \
  username=your-actual-email@gmail.com \
  password=your-16-character-app-password \
  from-address=your-actual-email@gmail.com \
  from-name="Your Name"
```

## Update Application Properties for Vault

Add these properties to reference Vault secrets:

```properties
# Gmail SMTP Configuration from Vault
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${email.username}
spring.mail.password=${email.password}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.starttls.required=true
spring.mail.properties.mail.smtp.ssl.trust=smtp.gmail.com

# Custom Email Configuration from Vault
app.email.enabled=true
app.email.default-from-address=${email.from-address}
app.email.default-from-name=${email.from-name}
```

## Vault Configuration

Make sure your `bootstrap.properties` or `application.properties` includes:

```properties
spring.cloud.vault.kv.enabled=true
spring.cloud.vault.kv.backend=secret
spring.cloud.vault.kv.profile-separator=/
spring.config.import=vault://secret/email
```
