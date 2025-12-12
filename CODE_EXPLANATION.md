# Jira-DocuSign Plugin - Complete Code Explanation

## 📋 Architecture Overview

This plugin integrates DocuSign eSignature with Jira by:
1. **REST Endpoint** (`DocusignRestResource.java`) - Receives requests from Jira
2. **Jira Attachment Extraction** - Reads attachments from Jira issues
3. **Base64 Encoding** - Converts attachments to Base64 for DocuSign
4. **DocuSign Service** (`DocusignService.java`) - Handles all DocuSign API calls
5. **JWT Authentication** - Authenticates with DocuSign using JWT
6. **Envelope Creation** - Creates and sends DocuSign envelopes

**Key Design Decision**: Uses direct REST API calls (Apache HttpClient) instead of DocuSign SDK to avoid Jakarta/Jersey 3.x compatibility issues with Jira's javax.ws.rs stack.

---

## 📁 File-by-File Breakdown

### 1. `pom.xml` - Maven Build Configuration

**Purpose**: Defines dependencies and OSGi bundle configuration.

**Key Dependencies**:
- `jira-api` - Jira plugin API (provided by Jira)
- `httpclient:4.5.14` - Apache HttpClient for REST calls
- `gson:2.10.1` - JSON parsing
- `java-jwt:4.4.0` - JWT token generation
- `bcprov-jdk15on:1.70` - RSA key handling

**Critical OSGi Configuration**:
```xml
<Embed-Dependency>
    groupId=org.apache.httpcomponents;artifactId=httpclient;inline=true;scope=compile,
    groupId=com.google.code.gson;artifactId=gson;inline=true;scope=compile,
    groupId=com.auth0;artifactId=java-jwt;inline=true;scope=compile,
    groupId=org.bouncycastle;artifactId=bcprov-jdk15on;inline=true;scope=compile
</Embed-Dependency>
```
This embeds dependencies into the OSGi bundle so Jira can load them.

**Error Prevention**:
- Excludes `org.apache.avalon.*` (not available in Jira)
- Makes `org.apache.avalon.*` and `org.apache.log.*` optional imports

---

### 2. `atlassian-plugin.xml` - Plugin Manifest

**Purpose**: Defines the plugin structure for Jira.

**Key Sections**:
- **REST Endpoint**: 
  ```xml
  <rest key="docusign-rest" path="/docusign" version="1.0">
  ```
  Maps to: `http://localhost:2990/jira/rest/docusign/1.0/send`

- **Web Panel**: Shows DocuSign panel in Jira issue view (right sidebar)

---

### 3. `DocusignRestResource.java` - REST API Endpoint

**Purpose**: Main REST endpoint that handles incoming requests.

**Endpoint**: `POST /rest/docusign/1.0/send`

**Request Body**:
```json
{
  "issueKey": "TEST-1"
}
```

**Flow**:
1. **Validates request** (line 33-37) - Checks if issueKey is provided
2. **Gets Jira issue** (line 40) - Retrieves issue using IssueManager
3. **Extracts attachments** (line 48-69):
   - Uses `AttachmentManager.streamAttachmentContent()` (Jira 9+ API)
   - Converts to byte array using `IOUtils.toByteArray()`
   - Encodes to Base64
   - Stores in `AttachmentData` objects
4. **Validates attachments** (line 72-76) - Returns 400 if no attachments
5. **Converts to DocuSign format** (line 79):
   - Calls `convertToDocusignDocuments()` to create `DocuSignDocument` objects
6. **Creates signers** (line 82-83) - **HARDCODED** for now: `testsigner@example.com`
7. **Calls DocuSign Service** (line 86-95):
   - Creates `DocusignService` instance
   - Calls `sendEnvelope()`
   - Returns `envelopeId` or error

**Response**:
```json
{
  "envelopeId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
}
```

**Helper Methods**:
- `convertToDocusignDocuments()` - Converts `AttachmentData` to `DocuSignDocument`
- `convertSigners()` - Converts `SignerRequest` to `DocuSignSigner` (not currently used)

**Inner Classes**:
- `SendRequest` - Request DTO
- `AttachmentData` - Stores filename and Base64 content
- `SignerRequest` - Signer DTO (for future use)

---

### 4. `DocusignService.java` - DocuSign API Integration

**Purpose**: Handles all DocuSign API interactions using direct REST calls.

#### Configuration (Lines 34-68):
```java
private final String basePath = "https://demo.docusign.net/restapi";
private final String authUrl = "https://account-d.docusign.com/oauth/token";
private final String integrationKey = "37a35ef8-eb8d-413a-b34c-b4a95eda8c8e";
private final String userId = "f900506f-da7a-4b14-8d6a-283775b775f2";
private final String accountId = "7392990d-1ae4-4c3e-a17a-408bba9394af";
private final String privateKey = "-----BEGIN RSA PRIVATE KEY-----...";
```

**Note**: Credentials are hardcoded. For production, load from environment variables or secure config.

#### Methods:

**1. `getPrivateKey()` (Line 75-85)**
- Parses PEM-formatted RSA private key
- Removes PEM headers and whitespace
- Decodes Base64
- Creates `RSAPrivateKey` object

**2. `generateJWT()` (Line 90-104)**
- Creates JWT token for DocuSign authentication
- Claims:
  - `iss` (issuer): Integration Key
  - `sub` (subject): User ID
  - `aud` (audience): `account-d.docusign.com`
  - `scope`: `"signature impersonation"`
  - `exp`: 1 hour expiration
- Signs with RSA256 algorithm

**3. `getAccessToken()` (Line 109-130)**
- Generates JWT using `generateJWT()`
- POSTs to `/oauth/token` with:
  - `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`
  - `assertion=<JWT_TOKEN>`
- Parses JSON response to extract `access_token`
- Returns access token or throws exception

**4. `sendEnvelope()` (Line 176-227)**
- Gets access token
- Builds envelope JSON:
  ```json
  {
    "emailSubject": "Documents to sign | Issue: <issueKey>",
    "status": "sent",
    "documents": [...],
    "recipients": {
      "signers": [...]
    }
  }
  ```
- POSTs to `/v2.1/accounts/{accountId}/envelopes`
- Returns `envelopeId` from response

**Inner Classes**:
- `DocuSignDocument` - Document model (base64, name, documentId)
- `DocuSignSigner` - Signer model (email, name, recipientId, routingOrder)

---

## 🔄 Complete Data Flow

```
1. Client POST → /rest/docusign/1.0/send
   Body: {"issueKey": "TEST-1"}
   
2. DocusignRestResource.sendDocument()
   ↓
3. Extract attachments from Jira issue
   ↓
4. Convert attachments to Base64
   ↓
5. Convert to DocuSignDocument objects
   ↓
6. Create DocuSignSigner objects (hardcoded)
   ↓
7. DocusignService.sendEnvelope()
   ↓
8. DocusignService.getAccessToken()
   ↓
9. DocusignService.generateJWT()
   - Create JWT with integration key, user ID
   - Sign with RSA private key
   ↓
10. POST to DocuSign /oauth/token
    - Exchange JWT for access token
   ↓
11. POST to DocuSign /accounts/{accountId}/envelopes
    - Create envelope with documents and signers
    - Returns envelopeId
   ↓
12. Return envelopeId to client
```

---

## 🐛 Common Errors & How to Check

### 1. **Plugin Not Loading (OSGi Bundle Errors)**

**Symptom**: 
```
FAILED PLUGIN: com.koushik.docusign.jira-docusign-plugin
osgi.wiring.package; (osgi.wiring.package=org.apache.xxx)
```

**Where to Check**:
```bash
# Check Jira logs for plugin loading errors
cd /Users/koushikvarma/jira-docusign-plugin
find target/jira/home/log -name "*.log" -type f | head -1 | xargs grep -i "FAILED PLUGIN\|BundleException\|osgi.wiring" | tail -20
```

**Fix**: 
- Check `pom.xml` `<Embed-Dependency>` includes all needed libraries
- Check `<Import-Package>` makes missing packages optional
- Rebuild: `atlas-clean && atlas-run`

---

### 2. **REST Endpoint Not Found (404)**

**Symptom**: 
```json
{"statusCode":404,"message":"No context path found"}
```

**Where to Check**:
```bash
# Test endpoint
curl -v -u admin:admin "http://localhost:2990/jira/rest/docusign/1.0/send" \
  -X POST -H "Content-Type: application/json" \
  -d '{"issueKey":"TEST-1"}'
```

**Fix**:
- Verify `atlassian-plugin.xml` has correct REST configuration
- Check plugin loaded: `grep "plugin.*enabled" target/jira/home/log/*.log`
- Restart Jira: `atlas-run`

---

### 3. **Issue Not Found (400 Bad Request)**

**Symptom**:
```json
{"error": "Invalid issue key"}
```

**Where to Check**:
- Check if issue exists in Jira
- Verify issueKey format (e.g., "TEST-1")
- Check Jira logs for exceptions

**Fix**:
- Create test issue first
- Use correct issue key format

---

### 4. **No Attachments (400 Bad Request)**

**Symptom**:
```json
{"error": "Issue has no attachments"}
```

**Where to Check**:
- Check if issue has attachments in Jira UI
- Verify `AttachmentManager.getAttachments(issue)` returns results

**Fix**:
- Upload at least one attachment to the issue before calling endpoint

---

### 5. **DocuSign Authentication Error**

**Symptom**:
```json
{"error": "Failed to get access token: ..."}
```

**Possible Causes**:
1. **Invalid JWT**: Wrong integration key, user ID, or private key
2. **JWT not consented**: User must consent to JWT authentication in DocuSign
3. **Wrong environment**: Using production credentials with demo environment (or vice versa)
4. **Expired/invalid private key**: Private key format incorrect

**Where to Check**:
```bash
# Check Jira logs for DocuSign errors
find target/jira/home/log -name "*.log" -type f | head -1 | xargs tail -200 | grep -i "docusign\|Exception\|ERROR" | tail -20
```

**Debug Steps**:
1. **Verify credentials** in `DocusignService.java`:
   - Integration Key matches DocuSign app
   - User ID is correct API User GUID
   - Account ID matches the account
   - Private key is correct (no extra spaces/characters)

2. **Check JWT consent**:
   - Go to DocuSign Admin → Apps & Keys → Your Integration
   - User must consent to JWT authentication
   - Or use Authorization Code flow for first-time consent

3. **Test JWT manually**:
   - Use Postman/curl to test JWT endpoint separately
   - Check JWT format with jwt.io

4. **Verify environment**:
   - Demo: `https://account-d.docusign.com`
   - Production: `https://account.docusign.com`
   - Base path: `https://demo.docusign.net/restapi` (demo) or `https://www.docusign.net/restapi` (prod)

**Fix**:
- Update credentials in `DocusignService.java`
- Ensure user has consented to JWT
- Match environment (demo vs production)

---

### 6. **Envelope Creation Error**

**Symptom**:
```json
{"error": "Failed to create envelope: ..."}
```

**Possible Causes**:
1. Invalid access token
2. Wrong account ID
3. Invalid document format (Base64 encoding issue)
4. Invalid signer information (email format, etc.)
5. Missing required fields in envelope JSON

**Where to Check**:
```bash
# Check detailed error in logs
find target/jira/home/log -name "*.log" -type f | head -1 | xargs tail -500 | grep -i -A 10 "docusign\|envelope\|Exception" | tail -30
```

**Debug Steps**:
1. **Check envelope JSON structure**:
   - Verify `documents` array has valid Base64
   - Verify `signers` array has valid email/name
   - Check required fields: `documentId`, `recipientId`, `routingOrder`

2. **Test with DocuSign API Explorer**:
   - Use DocuSign API Explorer to test envelope creation
   - Compare JSON structure

3. **Verify Base64 encoding**:
   - Check if Base64 string is valid
   - Ensure no newlines/whitespace in Base64

**Fix**:
- Validate all envelope fields
- Check Base64 encoding
- Verify signer email format

---

## 🔍 Debugging Guide

### Step 1: Check Build Status
```bash
cd /Users/koushikvarma/jira-docusign-plugin
mvn clean compile
# Look for compilation errors
```

### Step 2: Check Plugin Loading
```bash
# Start Jira
atlas-run

# In another terminal, check logs
tail -f target/jira/home/log/atlassian-jira.log | grep -i "docusign\|FAILED PLUGIN\|BundleException"
```

### Step 3: Test REST Endpoint
```bash
# Wait for Jira to start (check http://localhost:2990/jira/status)

# Test endpoint
curl -v -u admin:admin "http://localhost:2990/jira/rest/docusign/1.0/send" \
  -X POST -H "Content-Type: application/json" \
  -d '{"issueKey":"TEST-1"}'
```

### Step 4: Check Detailed Errors
```bash
# Check recent logs
find target/jira/home/log -name "*.log" -type f | head -1 | xargs tail -1000 | grep -i -E "ERROR|Exception|docusign" | tail -50
```

### Step 5: Verify DocuSign Credentials
1. Check `DocusignService.java` lines 38-68 for credentials
2. Verify in DocuSign Admin portal:
   - Integration Key matches
   - User ID is correct
   - Account ID matches
   - Private key matches (from key pair)
3. Ensure user has consented to JWT authentication

---

## 🚨 Important Notes

### Security Concerns:
1. **Private Key**: Currently hardcoded in source code. **Production**: Store in secure vault/environment variable
2. **Credentials**: All credentials are hardcoded. **Production**: Use configuration properties

### Limitations:
1. **Hardcoded Signer**: Currently uses `testsigner@example.com`. Should accept from request
2. **No Error Handling for Network Issues**: Add retry logic for DocuSign API calls
3. **No Logging**: Add proper logging for debugging

### Future Improvements:
1. Accept signers from request body
2. Add configuration UI in Jira admin
3. Store credentials securely
4. Add envelope status tracking
5. Add webhook support for DocuSign events

---

## ✅ Testing Checklist

- [ ] Plugin builds without errors: `mvn clean compile`
- [ ] Plugin loads in Jira (no FAILED PLUGIN errors)
- [ ] REST endpoint accessible: `curl http://localhost:2990/jira/rest/docusign/1.0/send`
- [ ] Issue exists with attachments
- [ ] DocuSign credentials are correct
- [ ] User has consented to JWT authentication
- [ ] Environment matches (demo vs production)
- [ ] Test envelope creation with valid issue key

