# 📧 Email Integrator

A Spring Boot microservice for sending templated emails with user approval workflows. Supports multiple email templates including approval requests, account notifications, and registration confirmations.

## 🚀 Production Status

**✅ LIVE & OPERATIONAL** - Deployed on AWS Elastic Beanstalk

| Base URL (Production) | Value |
|-----------------------|--------------------------------------------------------------------------|
| Elastic Beanstalk     | `http://email-integrator-prod.eba-p4bnt2xm.us-east-1.elasticbeanstalk.com` |
| Production Domain     | http://api.email.hoseacodes.com/                                         |

### 🌐 Production API

#### Send Email
```bash
POST /auth/send-email
Content-Type: application/json

{
  "email": "user@example.com",
  "name": "User Name",
  "templateType": "approval|approved|denied|pending",
  "appName": "Your App",
  "appDisplayName": "Your Application Suite",
  "approvalUrl": "https://yourapp.com/approve",
  "denyUrl": "https://yourapp.com/deny"
}
```

#### Health Check
```bash
GET /actuator/health
```

## 📧 Email Templates

- **`approval`** - Send approval request to admin with approve/deny buttons
- **`approved`** - Notify user their account was approved
- **`denied`** - Notify user their account was denied  
- **`pending`** - Confirm registration is pending admin review

## 🚀 Quick Deployment

### One-Command Deploy to AWS
```bash
./eb-deploy.sh
```

## 📚 Documentation

Project documentation lives in Notion: **[Email Integrator — Docs](https://app.notion.com/p/379b1345b43e812098facfe43a28b7d2)**

- **[🚀 Deployment (Quick Guide)](https://app.notion.com/p/379b1345b43e81fb9c0ed699037471ff)** - Quick deployment instructions
- **[🛠️ Deployment & Operations Guide](https://app.notion.com/p/379b1345b43e81f2894fd5716f3361eb)** - Full deployment & ops reference
- **[🧪 API Testing Examples](https://app.notion.com/p/379b1345b43e81b09070c58168e61f5e)** - Complete API testing examples
- **[📧 Email Templates](https://app.notion.com/p/379b1345b43e81ee80dfd4f6014ccfbd)** - Email template documentation
- **[⚙️ Environment Setup](https://app.notion.com/p/379b1345b43e8115af75db27e5903bb6)** - Environment variable configuration
- **[📮 Gmail Setup](https://app.notion.com/p/379b1345b43e81ba95c7edfc2c4ee2b2)** - Gmail configuration guide
- **[🔒 SSL / HTTPS Best Practices](https://app.notion.com/p/379b1345b43e81adae74d15fa026691e)** - Production SSL guidance
- **[🔐 Vault Email Setup](https://app.notion.com/p/379b1345b43e81589398cf83b1862322)** - Storing Gmail credentials in Vault

## 🔧 Configuration

Required environment variables:
- `MAIL_PASSWORD` - Gmail app password
- `JWT_SECRET` - 256-bit secret key for JWT tokens

---

## Resources and URI Mappings

- Send email example - POST /email
- Get app health - GET /actuator/health

## Build Application

As part of the Maven build, automatically (Maven will invoke the whole build, hence each and every phase till the install, as such any execution listed in the POM with related bindings)


```bash
mvn clean install
```

As part of an invocation to the specific phase: Maven will invoke all the executions with a bind to it (and any previous phase), hence not only the specific execution you mentioned

```bash
mvn generate-sources
```

Make Maven to copy dependencies into target/lib supposing
- you don't want to alter the pom.xml
- you don't want test scoped (e.g. junit.jar) or provided dependencies (e.g. wlfullclient.jar)

```bash
mvn install dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
```

## Run Application

```bash
mvn spring-boot:run
```

## API Services
- [Brevo](https://github.com/sendinblue/APIv3-java-library?tab=readme-ov-file)

### Vault

Serve vault locally @ http://127.0.0.1:8200/ui/vault/auth?with=token

```bash
vault server -dev
```

### Docker

Build Docker Image

```bash
 docker build -t hoseacodes-emailintegrator .    
 ```

Run Docker Image

```bash
docker run -p 8080:8080 hoseacodes-emailintegrator
```

Tage Docker Image for Push

```bash
docker tag ${imageID} hoseacodes/hoseacodes-emailintegrator:latest
```

Push Docker Image 

```bash
docker push hoseacodes/hoseacodes-emailintegrator:latest        
```

## 🛠️ Tech Stack & Tools

### Core Technologies
- **Java 17** - Runtime environment
- **Spring Boot 3.2.5** - Application framework
- **Spring Mail** - Email sending capabilities
- **JWT** - Token-based authentication
- **Thymeleaf** - HTML email templating
- **Maven** - Build and dependency management
- **Docker** - Containerization
- **AWS Elastic Beanstalk** - Cloud deployment

### Original Tools
- Java 17
- Maven
- Spring Boot Starter

## ✅ Current Features

- ✅ **Production Ready** - Deployed on AWS Elastic Beanstalk
- ✅ **Multiple Email Templates** - Approval, notification, and confirmation emails
- ✅ **JWT Integration** - Secure token-based approval links
- ✅ **Gmail Integration** - SMTP email sending via Gmail
- ✅ **Docker Support** - Containerized deployment
- ✅ **Health Monitoring** - Built-in health check endpoints
- ✅ **Error Handling** - Comprehensive error handling and logging

## Future Enhancements 

- [ ] [SSL Cert](./src/main/resources/docs/SSL.md)
- [ ] [Deploy to Azure](https://spring.io/guides/gs/spring-boot-for-azure)
- [ ] [Implement Basic Auth](https://medium.com/javarevisited/spring-boot-securing-api-with-basic-authentication-bdd3ad2266f5)
  - [Another Basic Auth Method](https://www.geeksforgeeks.org/spring-security-basic-authentication/)
- [ ] [Basic Auth RESTTemplate](https://www.baeldung.com/how-to-use-resttemplate-with-basic-authentication-in-spring)
- [ ] [Add Swagger]()
- [ ] [Add Junit Tests]()
- [ ] [Add PIT Tests]()
- [ ] [Add Karate Tests]()
- [ ] [Add Test Thresholds]()
- [ ] [Create & Deploy Evidence of Tests]()
- [ ] [Add Github Actions]()
  - [ ] Build Job
  - [ ] Deploy Job
  - [ ] Add Snyk scans
  - [ ] Add linter job
  - [ ] Test job
  - [ ] Secret scan

---

## 🎯 Project Status

**✅ PRODUCTION READY** - The Email Integrator is fully deployed and operational on AWS Elastic Beanstalk!

- **Live URL:** `http://email-integrator-prod.eba-p4bnt2xm.us-east-1.elasticbeanstalk.com`
- **Status:** All email templates working ✅
- **Gmail Integration:** Configured and functional ✅  
- **JWT Authentication:** Implemented and secure ✅
- **Error Handling:** Comprehensive null-safe implementation ✅
- **Documentation:** Complete with examples and guides ✅

**Ready for production use!** 🚀 