# Email Templates Documentation

## Template Structure

All HTML email templates are now stored in `src/main/resources/templates/` directory for better organization and maintainability.

## Available Templates

### 1. `approval-email.html`
**Purpose:** Sent to admin when a new user registers and needs approval  
**Variables:**
- `{{userName}}` - Name of the user requesting approval
- `{{userEmail}}` - Email of the user requesting approval
- `{{approvalToken}}` - JWT token for approval process
- `{{approvalUrl}}` - URL to approve the user
- `{{denyUrl}}` - URL to deny the user
- `{{appName}}` - Application name (configurable)
- `{{appDisplayName}}` - Application display name (configurable)

### 2. `account-approved.html`
**Purpose:** Sent to user when their account is approved  
**Variables:**
- `{{userName}}` - Name of the approved user
- `{{loginUrl}}` - URL to login to the application
- `{{appName}}` - Application name (configurable)
- `{{appDisplayName}}` - Application display name (configurable)

### 3. `account-denied.html`
**Purpose:** Sent to user when their account is denied  
**Variables:**
- `{{userName}}` - Name of the user
- `{{adminEmail}}` - Admin contact email for support
- `{{appName}}` - Application name (configurable)
- `{{appDisplayName}}` - Application display name (configurable)

### 4. `registration-pending.html`
**Purpose:** Sent to user confirming their registration is pending approval  
**Variables:**
- `{{userName}}` - Name of the user
- `{{adminEmail}}` - Admin contact email for questions
- `{{appName}}` - Application name (configurable)
- `{{appDisplayName}}` - Application display name (configurable)

## Template Service

The `EmailTemplateService` handles:
- Loading templates from the resources directory
- Variable substitution using `{{variableName}}` syntax
- Fallback to default templates if file loading fails
- Error handling and logging

## Configuration

Configure your application name and display name in `application.properties`:

```properties
# User Approval Configuration
app.name=Your App Name
app.display-name=Your App Display Name
app.base-url=http://localhost:8080
app.admin-email=admin@yourapp.com
```

## Usage Example

```java
@Autowired
private UserApprovalEmailService emailService;

// Send approval email
UserData userData = new UserData("user@example.com", "John Doe");
boolean sent = emailService.sendApprovalEmail(userData);
```

## Customizing Templates

1. **Edit existing templates:** Modify files in `src/main/resources/templates/`
2. **Add new variables:** Update the corresponding method in `UserApprovalEmailService`
3. **Create new templates:** Add new HTML files and update `EmailTemplateService`

## Template Variables Format

- Use double curly braces: `{{variableName}}`
- Variables are case-sensitive
- Unused variables will be replaced with empty strings
- HTML content should be properly escaped

## Fallback Behavior

If a template file cannot be loaded:
1. The service logs an error
2. A default minimal template is used
3. The email is still sent (degraded functionality)
4. Check logs for template loading issues

## File Structure

```
src/main/resources/templates/
├── approval-email.html
├── account-approved.html
├── account-denied.html
└── registration-pending.html
```
