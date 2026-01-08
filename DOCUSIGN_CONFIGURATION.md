# DocuSign Configuration Guide

## 🔐 **Environment Variables or JVM Properties**

The `DocusignService` reads configuration from environment variables or JVM system properties (in that order).

### **Required Configuration**

Set these **one** of the following ways:

#### **Option 1: Environment Variables (Recommended for Production)**

```bash
export DOCUSIGN_CLIENT_ID="your-integration-key"
export DOCUSIGN_USER_ID="your-user-id"
export DOCUSIGN_ACCOUNT_ID="your-account-id"
export DOCUSIGN_PRIVATE_KEY_PEM="$(cat /path/to/docusign_private_key.pem)"
```

#### **Option 2: JVM System Properties**

```bash
java -DDOCUSIGN_CLIENT_ID=your-key \
     -DDOCUSIGN_USER_ID=your-user-id \
     -DDOCUSIGN_ACCOUNT_ID=your-account-id \
     -DDOCUSIGN_PRIVATE_KEY_PEM="$(cat private_key.pem)" \
     ...
```

#### **Option 3: For Jira Development (atlas-run)**

Create a file `setenv.sh` in your project root:

```bash
#!/bin/bash
export DOCUSIGN_CLIENT_ID="your-integration-key"
export DOCUSIGN_USER_ID="your-user-id"
export DOCUSIGN_ACCOUNT_ID="your-account-id"
export DOCUSIGN_PRIVATE_KEY_PEM="-----BEGIN PRIVATE KEY-----
Your private key content
-----END PRIVATE KEY-----"
```

Then run:
```bash
source setenv.sh
atlas-run
```

### **Optional Configuration**

These have defaults (for Demo environment):

- `DOCUSIGN_OAUTH_BASE` (default: `https://account-d.docusign.com`)
- `DOCUSIGN_REST_BASE` (default: `https://demo.docusign.net/restapi`)

#### **Webhooks (Optional, Recommended)**

Enable near-instant status updates by having DocuSign call back into Jira using Connect `eventNotification`.

- `DOCUSIGN_WEBHOOK_URL` (no default)
  - Example (ngrok): `https://<your-ngrok-domain>/jira/rest/docusign/1.0/send/webhook`
  - The plugin appends `?issueKey=KEY-123` automatically when sending an envelope.
- `DOCUSIGN_CONNECT_HMAC_KEY` (optional but recommended)
  - If set, the webhook receiver validates `X-DocuSign-Signature-1/2`.
- `DOCUSIGN_WEBHOOK_ACTOR` (default: `admin`)
  - Jira user key/name used to write issue properties when webhook calls are anonymous.

**Local dev with ngrok**

```bash
ngrok http 2990
export DOCUSIGN_WEBHOOK_URL="https://<your-ngrok-domain>/jira/rest/docusign/1.0/send/webhook"
# Optional security (recommended):
export DOCUSIGN_CONNECT_HMAC_KEY="your-hmac-key"
export DOCUSIGN_WEBHOOK_ACTOR="admin"
```

#### **For Production Environment:**

```bash
export DOCUSIGN_OAUTH_BASE="https://account.docusign.com"
export DOCUSIGN_REST_BASE="https://www.docusign.net/restapi"
```

---

## 📋 **Configuration Values Explained**

### **DOCUSIGN_CLIENT_ID** (Integration Key)
- Found in: DocuSign Admin → Apps & Keys → Integration
- This is your Integration Key (also called Client ID)

### **DOCUSIGN_USER_ID** (API User GUID)
- Found in: DocuSign Admin → Users → [Select User] → User ID
- Must be the User ID (GUID) of the user you're impersonating
- This user must have granted consent for JWT authentication

### **DOCUSIGN_ACCOUNT_ID**
- Found in: DocuSign Admin → Settings → Account ID
- Your DocuSign Account ID

### **DOCUSIGN_PRIVATE_KEY_PEM**
- RSA Private Key in PEM format
- Must match the public key uploaded to DocuSign Integration
- Format:
  ```
  -----BEGIN PRIVATE KEY-----
  [base64 encoded key content]
  -----END PRIVATE KEY-----
  ```
- **Full key including BEGIN/END markers must be provided**

---

## 🔑 **Getting Your Private Key**

### **If You Already Have a Key Pair:**

1. Ensure the **public key** is uploaded to your DocuSign Integration
2. Use the **private key** as `DOCUSIGN_PRIVATE_KEY_PEM`

### **Generating a New Key Pair:**

```bash
# Generate RSA private key
openssl genrsa -out private_key.pem 2048

# Extract public key
openssl rsa -in private_key.pem -pubout -out public_key.pem

# Upload public_key.pem to DocuSign Integration settings
# Use private_key.pem as DOCUSIGN_PRIVATE_KEY_PEM
```

**Note**: The private key must be kept secure! Never commit it to version control.

---

## ✅ **Verification**

When `DocusignService` is instantiated, it will throw an `IllegalStateException` if any required configuration is missing:

```
Missing config: DOCUSIGN_CLIENT_ID (set env var or -DDOCUSIGN_CLIENT_ID)
```

This happens at **construction time**, so errors are caught early.

---

## 🔒 **Security Best Practices**

1. **Never commit credentials to Git**
   - Add `setenv.sh` to `.gitignore`
   - Use environment variables in production
   - Consider using secrets management (AWS Secrets Manager, HashiCorp Vault, etc.)

2. **Use environment-specific values**
   - Demo environment: `account-d.docusign.com`, `demo.docusign.net`
   - Production environment: `account.docusign.com`, `www.docusign.net`

3. **Rotate keys periodically**
   - Generate new key pairs
   - Upload new public key to DocuSign
   - Update `DOCUSIGN_PRIVATE_KEY_PEM` environment variable

---

## 🧪 **Testing Configuration**

To test if configuration is loaded correctly, add a simple test:

```java
DocusignService service = new DocusignService();
// If no exception is thrown, configuration is valid
```

Or check Jira logs for initialization errors.

---

## 📝 **Example: Complete Setup**

```bash
# 1. Set environment variables
export DOCUSIGN_CLIENT_ID="12345678-1234-1234-1234-123456789012"
export DOCUSIGN_USER_ID="87654321-4321-4321-4321-210987654321"
export DOCUSIGN_ACCOUNT_ID="11223344-5566-7788-9900-aabbccddeeff"

# 2. Read private key (including markers)
export DOCUSIGN_PRIVATE_KEY_PEM="$(cat /path/to/private_key.pem)"

# 3. Optional: Set production URLs
# export DOCUSIGN_OAUTH_BASE="https://account.docusign.com"
# export DOCUSIGN_REST_BASE="https://www.docusign.net/restapi"

# 4. Start Jira
atlas-run
```

---

## 🔧 **Troubleshooting**

### **Error: "Missing config: DOCUSIGN_CLIENT_ID"**

**Solution**: Ensure environment variable or JVM property is set before Jira starts.

### **Error: "Failed to parse private key"**

**Solution**: 
- Ensure private key includes `-----BEGIN PRIVATE KEY-----` and `-----END PRIVATE KEY-----` markers
- Key must be in PKCS#8 format (not PKCS#1)
- If you have PKCS#1 format (`-----BEGIN RSA PRIVATE KEY-----`), convert it:
  ```bash
  openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in rsa_key.pem -out pkcs8_key.pem
  ```

### **Error: "DocuSign token response missing access_token"**

**Possible Causes**:
- Wrong `DOCUSIGN_CLIENT_ID` or `DOCUSIGN_USER_ID`
- User hasn't granted consent for JWT authentication
- Wrong `DOCUSIGN_OAUTH_BASE` (should match account server)
- Private key doesn't match public key in DocuSign

**Solution**: 
- Grant consent: https://account-d.docusign.com/oauth/auth?response_type=code&scope=signature%20impersonation&client_id={CLIENT_ID}&redirect_uri={REDIRECT_URI}
- Verify public key matches private key

---

**Configuration is now managed via environment variables - much cleaner and more secure!** ✅
