# Gmail App Password Setup Guide

## Step 1: Enable 2-Factor Authentication
1. Go to your Google Account: https://myaccount.google.com/
2. Click on **Security** in the left sidebar
3. Under "Signing in to Google", click **2-Step Verification**
4. Follow the prompts to enable 2FA if not already enabled

## Step 2: Generate App Password
1. Go to **App passwords**: https://myaccount.google.com/apppasswords
2. Select **Mail** from the "Select app" dropdown
3. Select **Other (Custom name)** from the "Select device" dropdown
4. Enter a name like "Email Integrator Service"
5. Click **Generate**
6. **Copy the 16-character password** (it looks like: `abcd efgh ijkl mnop`)

## Step 3: Set Environment Variables

### Option A: Export in Terminal (Temporary)
```bash
export MAIL_USERNAME=your-actual-email@gmail.com
export MAIL_PASSWORD=abcdefghijklmnop  # Your 16-char app password (no spaces)
export MAIL_FROM_ADDRESS=your-actual-email@gmail.com
export MAIL_FROM_NAME="Your Name"
```

### Option B: Create .env file (Recommended)
```bash
# Create .env file in your project root
cp .env.example .env

# Edit .env file with your actual credentials
MAIL_USERNAME=your-actual-email@gmail.com
MAIL_PASSWORD=abcdefghijklmnop
MAIL_FROM_ADDRESS=your-actual-email@gmail.com
MAIL_FROM_NAME=Your Name
```

## Step 4: Restart Your Application
After setting the environment variables, restart your Spring Boot application.

## Step 5: Test the Email
```bash
curl -X POST http://localhost:8080/api/spring-mail/send-simple \
  -H "Content-Type: application/json" \
  -d '{
    "to": "recipient@example.com",
    "subject": "Test Email from Gmail",
    "text": "Hello from Spring Boot Mail with Gmail!"
  }'
```

## Troubleshooting

### "Authentication failed" Error
- Double-check your Gmail address and App Password
- Make sure 2FA is enabled on your Google account
- Ensure the App Password has no spaces when setting the environment variable

### "Less secure app access" Error
- This shouldn't happen with App Passwords, but if it does:
- Go to https://myaccount.google.com/lesssecureapps
- Turn on "Allow less secure apps" (not recommended, use App Password instead)

### Environment Variables Not Loading
- Make sure you've exported the variables in the same terminal session
- Or restart your IDE/terminal after setting system environment variables
- Check that variable names match exactly: `MAIL_USERNAME`, `MAIL_PASSWORD`, etc.

## Security Notes
- **Never commit your App Password to version control**
- App Passwords are specific to applications - you can revoke them anytime
- Use different App Passwords for different applications
- The `.env` file should be added to `.gitignore`
