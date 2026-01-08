# Jira REST Endpoint Testing Guide

## 🔗 **Endpoint**

**POST** `/rest/docusign/1.0/send`

**Full URL**: `http://localhost:2990/jira/rest/docusign/1.0/send`

---

## 📋 **cURL Examples**

### **Example 1: Basic Request (Single Signer)**

```bash
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "issueKey": "TEST-1",
    "signers": [
      {
        "name": "John Doe",
        "email": "john@example.com",
        "order": "1"
      }
    ]
  }' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

### **Example 2: Multiple Signers**

```bash
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "issueKey": "PROJ-123",
    "signers": [
      {
        "name": "Alice Smith",
        "email": "alice@example.com",
        "order": "1"
      },
      {
        "name": "Bob Johnson",
        "email": "bob@example.com",
        "order": "2"
      }
    ]
  }' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

### **Example 3: With Pretty Print Response**

```bash
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "issueKey": "TEST-1",
    "signers": [{"name": "John", "email": "john@test.com", "order": "1"}]
  }' \
  http://localhost:2990/jira/rest/docusign/1.0/send | jq .
```

---

## 📮 **Postman Setup**

### **Request Configuration**

1. **Method**: `POST`
2. **URL**: `http://localhost:2990/jira/rest/docusign/1.0/send`

### **Headers**

| Key | Value |
|-----|-------|
| `Content-Type` | `application/json` |

### **Authorization**

- **Type**: Basic Auth
- **Username**: `admin`
- **Password**: `admin`

### **Body (raw JSON)**

```json
{
  "issueKey": "TEST-1",
  "signers": [
    {
      "name": "John Doe",
      "email": "john@example.com",
      "order": "1"
    }
  ]
}
```

---

## ✅ **Expected Success Response**

```json
{
  "status": "sent",
  "envelopeId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**HTTP Status**: `200 OK`

---

## ❌ **Expected Error Responses**

### **1. Missing Issue Key**

```json
{
  "error": "Missing or invalid issue key"
}
```

**HTTP Status**: `400 Bad Request`

### **2. Invalid Issue Key**

```json
{
  "error": "Invalid issue key: INVALID-999"
}
```

**HTTP Status**: `400 Bad Request`

### **3. No Attachments**

```json
{
  "error": "Issue has no attachments"
}
```

**HTTP Status**: `400 Bad Request`

### **4. Missing Signers**

```json
{
  "error": "At least one signer is required"
}
```

**HTTP Status**: `400 Bad Request`

### **5. DocuSign Configuration Error**

```json
{
  "error": "Failed to send envelope: Missing config: DOCUSIGN_CLIENT_ID (set env var or -DDOCUSIGN_CLIENT_ID)"
}
```

**HTTP Status**: `500 Internal Server Error`

### **6. DocuSign API Error**

```json
{
  "error": "Failed to send envelope: HTTP 401 from DocuSign: {\"error\":\"invalid_grant\",\"error_description\":\"consent_required\"}"
}
```

**HTTP Status**: `500 Internal Server Error`

---

## 🧪 **Pre-Testing Checklist**

Before testing, ensure:

1. ✅ Jira is running on `localhost:2990`
2. ✅ Jira issue exists (e.g., `TEST-1`)
3. ✅ Issue has at least one attachment
4. ✅ DocuSign environment variables are set:
   - `DOCUSIGN_CLIENT_ID`
   - `DOCUSIGN_USER_ID`
   - `DOCUSIGN_ACCOUNT_ID`
   - `DOCUSIGN_PRIVATE_KEY_PEM`
5. ✅ DocuSign user has granted JWT consent

---

## 🔍 **Testing Workflow**

### **Step 1: Verify Jira is Running**

```bash
curl http://localhost:2990/jira/status
```

Should return: `{"state":"RUNNING"}`

### **Step 2: Create Test Issue with Attachment**

1. Go to: http://localhost:2990/jira
2. Login: `admin` / `admin`
3. Create issue (e.g., `TEST-1`)
4. Upload a PDF or document attachment

### **Step 3: Test Endpoint**

Use one of the cURL examples above or Postman.

### **Step 4: Check Response**

- **Success**: You'll get `envelopeId` - check DocuSign to see the envelope
- **Error**: Check the error message for troubleshooting

---

## 🐛 **Troubleshooting**

### **401 Unauthorized**

**Problem**: Not authenticated to Jira

**Solution**: Ensure you're using correct credentials (`-u admin:admin`)

### **404 Not Found**

**Problem**: Plugin not loaded or wrong URL

**Solution**: 
- Check plugin is installed: http://localhost:2990/jira/plugins/servlet/upm/manage/all
- Verify URL: `/rest/docusign/1.0/send` (not `/rest/docusign/1.0/send/`)

### **500 Internal Server Error - Missing Config**

**Problem**: DocuSign environment variables not set

**Solution**: Set required environment variables before starting Jira

### **500 Internal Server Error - Consent Required**

**Problem**: DocuSign user hasn't granted JWT consent

**Solution**: 
1. Go to: https://account-d.docusign.com/oauth/auth?response_type=code&scope=signature%20impersonation&client_id={YOUR_CLIENT_ID}&redirect_uri=https://www.docusign.com
2. Grant consent
3. Retry the request

### **400 Bad Request - No Attachments**

**Problem**: Issue has no attachments

**Solution**: Add at least one attachment to the issue before calling the endpoint

---

## 📊 **Postman Collection (JSON)**

Save this as `Jira-DocuSign.postman_collection.json`:

```json
{
  "info": {
    "name": "Jira DocuSign Plugin",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Send to DocuSign",
      "request": {
        "method": "POST",
        "header": [
          {
            "key": "Content-Type",
            "value": "application/json"
          }
        ],
        "auth": {
          "type": "basic",
          "basic": [
            {
              "key": "username",
              "value": "admin",
              "type": "string"
            },
            {
              "key": "password",
              "value": "admin",
              "type": "string"
            }
          ]
        },
        "body": {
          "mode": "raw",
          "raw": "{\n  \"issueKey\": \"TEST-1\",\n  \"signers\": [\n    {\n      \"name\": \"John Doe\",\n      \"email\": \"john@example.com\",\n      \"order\": \"1\"\n    }\n  ]\n}"
        },
        "url": {
          "raw": "http://localhost:2990/jira/rest/docusign/1.0/send",
          "protocol": "http",
          "host": ["localhost"],
          "port": "2990",
          "path": ["jira", "rest", "docusign", "1.0", "send"]
        }
      }
    }
  ]
}
```

Import this into Postman for quick testing!

---

## ✅ **Success Indicators**

- ✅ HTTP 200 status
- ✅ Response contains `"status": "sent"`
- ✅ Response contains `"envelopeId"` (UUID format)
- ✅ Envelope appears in DocuSign account
- ✅ Signer receives email notification

---

**Happy Testing!** 🚀


