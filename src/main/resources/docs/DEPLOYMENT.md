# 🚀 Email Integrator - Quick Deployment Guide

## Prerequisites
- AWS CLI configured with appropriate permissions
- Docker installed (for local testing)
- Gmail app password (see GMAIL_SETUP.md)

## 🎯 One-Command Deployment

```bash
# Deploy to AWS Elastic Beanstalk
./eb-deploy.sh
```

## 🔧 Configuration

Update these environment variables in AWS Elastic Beanstalk:

```bash
# Required
MAIL_PASSWORD=your_gmail_app_password
JWT_SECRET=your_256_bit_secret_key

# Optional (have defaults)
SPRING_PROFILES_ACTIVE=prod
SPRING_CLOUD_VAULT_ENABLED=false
```

## 📧 API Usage

```bash
POST /auth/send-email
Content-Type: application/json

{
  "email": "user@example.com",
  "name": "User Name",
  "templateType": "approval|approved|denied|pending"
}
```

## 🏥 Health Check

```bash
GET /actuator/health
```

## 📚 Documentation

- **API Examples:** `API_TESTING_EXAMPLES.md`
- **Gmail Setup:** `GMAIL_SETUP.md`
- **Email Templates:** `EMAIL_TEMPLATES.md`

---

**Production URL:** `http://email-integrator-prod.eba-p4bnt2xm.us-east-1.elasticbeanstalk.com`
