# Environment Setup Guide

## Overview
This application uses environment variables to securely manage sensitive configuration values like JWT secrets and email passwords.

## Setup Instructions

### 1. Create Environment File
Copy the example environment file and fill in your actual values:
```bash
cp .env.example .env
```

### 2. Configure Required Variables
Edit the `.env` file and set the following required variables:

#### JWT Configuration
- `JWT_SECRET`: A secure secret key for JWT token generation (minimum 32 characters recommended)

#### Email Configuration  
- `MAIL_PASSWORD`: Your Gmail app password (not your regular Gmail password)

### 3. Gmail App Password Setup
To get a Gmail app password:
1. Enable 2-factor authentication on your Gmail account
2. Go to Google Account settings > Security > 2-Step Verification
3. Generate an "App Password" for this application
4. Use the generated 16-character password as your `MAIL_PASSWORD`

### 4. Optional Variables
You can also override these default settings in your `.env` file:
- `MAIL_USERNAME`: Email address (defaults to info@ambitiousconcept.com)
- `MAIL_HOST`: SMTP host (defaults to smtp.gmail.com)
- `MAIL_PORT`: SMTP port (defaults to 587)
- `BASE_URL`: Application base URL (defaults to http://localhost:8080)
- `ADMIN_EMAIL`: Admin email address (defaults to info@ambitiousconcept.com)
- `APP_NAME`: Application name (defaults to Email Integrator)

## Security Notes

- **Never commit `.env` files to version control**
- The `.env` file is already added to `.gitignore`
- Use strong, unique values for `JWT_SECRET`
- Rotate secrets regularly in production environments
- Consider using a proper secret management service for production deployments

## Running the Application

After setting up your `.env` file, you can run the application normally:
```bash
./mvnw spring-boot:run
```

The application will automatically load the environment variables from your `.env` file.
