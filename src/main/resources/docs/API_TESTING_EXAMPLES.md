# Email Template Testing - Unified Endpoint

## 📧 Single Endpoint for All Templates

Use the unified `/auth/send-email` endpoint with different `templateType` values to test all email templates.

---

## **🧪 Test All Email Templates**

### **1. Test Approval Email Template**
```bash
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john.doe@example.com",
    "name": "John Doe",
    "templateType": "approval",
    "appName": "MyApp",
    "appDisplayName": "My Application Suite",
    "approvalUrl": "https://myapp.com/admin/approve",
    "denyUrl": "https://myapp.com/admin/deny"
  }'
```
**Template:** `approval-email.html`  
**Recipient:** Admin (info@ambitiousconcept.com)  
**Content:** New user registration approval request with approve/deny buttons

---

### **2. Test Account Approved Email Template**
```bash
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "jane.smith@example.com",
    "name": "Jane Smith",
    "templateType": "approved",
    "appName": "ShopMaster",
    "appDisplayName": "ShopMaster E-commerce Platform",
    "loginUrl": "https://app.shopmaster.com/login"
  }'
```
**Template:** `account-approved.html`  
**Recipient:** User (jane.smith@example.com)  
**Content:** Account approval notification with login button

---

### **3. Test Account Denied Email Template**
```bash
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "bob.wilson@example.com",
    "name": "Bob Wilson",
    "templateType": "denied",
    "appName": "BlogHub",
    "appDisplayName": "BlogHub Content Management"
  }'
```
**Template:** `account-denied.html`  
**Recipient:** User (bob.wilson@example.com)  
**Content:** Account denial notification with support contact

---

### **4. Test Registration Pending Email Template**
```bash
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice.johnson@example.com",
    "name": "Alice Johnson",
    "templateType": "pending",
    "appName": "ClientPro",
    "appDisplayName": "ClientPro CRM Suite"
  }'
```
**Template:** `registration-pending.html`  
**Recipient:** User (alice.johnson@example.com)  
**Content:** Registration confirmation with pending approval status

---

## **📋 Payload Parameters**

### **Required Fields:**
- `email` - Recipient email address
- `name` - User's name
- `templateType` - Template to use (`approval`, `approved`, `denied`, `pending`)

### **Optional Fields:**
- `appName` - Application name (fallback to config if not provided)
- `appDisplayName` - Application display name (fallback to config if not provided)
- `approvalUrl` - Custom approval URL base (token will be appended automatically)
- `denyUrl` - Custom deny URL base (token will be appended automatically)
- `loginUrl` - Custom login URL (used in approved email template)

### **Example with Fallback:**
```bash
# Without optional fields - uses configuration defaults
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "name": "Test User",
    "templateType": "approved"
  }'
```

### **Custom URL Examples:**
```bash
# Custom approval URLs (for approval template)
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@company.com",
    "name": "New User",
    "templateType": "approval",
    "appName": "CompanyApp",
    "approvalUrl": "https://admin.company.com/approve-user",
    "denyUrl": "https://admin.company.com/deny-user"
  }'

# URLs with existing parameters
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@company.com",
    "name": "New User", 
    "templateType": "approval",
    "approvalUrl": "https://admin.company.com/approve?source=email",
    "denyUrl": "https://admin.company.com/deny?source=email"
  }'
```

**Note:** The JWT token will be automatically appended as `&token=...` or `?token=...` depending on whether the URL already has parameters.

### **Custom Login URL Examples:**
```bash
# Custom login URL (for approved template)
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@company.com",
    "name": "John Doe",
    "templateType": "approved",
    "appName": "CompanyApp",
    "loginUrl": "https://app.company.com/dashboard"
  }'

# Mobile app deep link
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@company.com",
    "name": "Jane Smith",
    "templateType": "approved",
    "appName": "MobileApp",
    "loginUrl": "myapp://login"
  }'

# Login with redirect parameter
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@company.com",
    "name": "Bob Wilson",
    "templateType": "approved",
    "loginUrl": "https://app.company.com/login?redirect=/welcome"
  }'
```

---

## **📋 Template Types Reference**

| Template Type | Template File | Purpose | Recipient |
|---------------|---------------|---------|-----------|
| `approval` | `approval-email.html` | Admin approval request | Admin |
| `approved` | `account-approved.html` | Account approved notification | User |
| `denied` | `account-denied.html` | Account denied notification | User |
| `pending` | `registration-pending.html` | Registration pending confirmation | User |

---

## **✅ Expected Response Format**

### **Success Response:**
```json
{
  "success": true,
  "message": "Account approved email sent to user",
  "templateType": "approved",
  "recipient": "jane.smith@example.com"
}
```

### **Error Response (Invalid Template Type):**
```json
{
  "error": "Invalid templateType",
  "message": "Valid types: approval, approved, denied, pending"
}
```

### **Error Response (Missing Fields):**
```json
{
  "error": "Email, name, and templateType are required"
}
```

---

## **🔄 Complete User Registration Flow Test**

Test the complete workflow with a single user:

```bash
# 1. User registers - send pending confirmation
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@example.com",
    "name": "New User",
    "templateType": "pending",
    "appName": "MyApp",
    "appDisplayName": "My Application Suite"
  }'

# 2. Admin gets approval request
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@example.com",
    "name": "New User",
    "templateType": "approval",
    "appName": "MyApp",
    "appDisplayName": "My Application Suite",
    "approvalUrl": "https://admin.myapp.com/approve-user",
    "denyUrl": "https://admin.myapp.com/deny-user"
  }'

# 3a. Admin approves (Option A)
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@example.com",
    "name": "New User",
    "templateType": "approved",
    "appName": "MyApp",
    "appDisplayName": "My Application Suite",
    "loginUrl": "https://app.myapp.com/dashboard"
  }'

# 3b. OR Admin denies (Option B)
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@example.com",
    "name": "New User",
    "templateType": "denied",
    "appName": "MyApp",
    "appDisplayName": "My Application Suite"
  }'
```

### **Complete Custom URL Example:**
```bash
# All URLs customized for a specific application
curl -X POST http://localhost:8080/auth/send-email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@company.com",
    "name": "John Doe",
    "templateType": "approval",
    "appName": "CompanyPortal",
    "appDisplayName": "Company Employee Portal",
    "approvalUrl": "https://admin.company.com/hr/approve-employee",
    "denyUrl": "https://admin.company.com/hr/deny-employee"
  }'
```

---

## **🎯 Benefits of Unified Endpoint**

### **Consistency:**
- ✅ Single endpoint for all email templates
- ✅ Consistent request/response format
- ✅ Easier API documentation and testing

### **Flexibility:**
- ✅ Easy to add new template types
- ✅ Template selection via payload parameter
- ✅ Simplified client integration

### **Maintainability:**
- ✅ Centralized email sending logic
- ✅ Single point of error handling
- ✅ Unified logging and monitoring

---

## **🔧 Integration Example**

### **JavaScript/Frontend Integration:**
```javascript
const sendEmail = async (email, name, templateType) => {
  const response = await fetch('/auth/send-email', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      email,
      name,
      templateType
    })
  });
  
  return response.json();
};

// Usage examples
await sendEmail('user@example.com', 'John Doe', 'pending');
await sendEmail('user@example.com', 'John Doe', 'approved');
```

### **Java Service Integration:**
```java
@Service
public class UserRegistrationService {
    
    @Autowired
    private UserApprovalEmailService emailService;
    
    public void handleUserRegistration(User user) {
        UserData userData = new UserData(user.getEmail(), user.getName());
        
        // Send pending confirmation to user
        emailService.sendRegistrationPendingEmail(userData);
        
        // Send approval request to admin
        emailService.sendApprovalEmail(userData);
    }
}
```
