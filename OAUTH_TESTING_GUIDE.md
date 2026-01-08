# DocuSign OAuth Flow - Manual Testing Guide

## 📋 Overview

This guide explains how to manually test the DocuSign OAuth Authorization Code flow with PKCE that we implemented in the Jira plugin.

---

## 🚀 **Step-by-Step Testing Process**

### **Step 1: Start Jira with Environment Variables**

```bash
# Make sure DocuSign credentials are set
source setenv.sh

# Start Jira
atlas-run
```

Wait for Jira to fully start (usually 1-2 minutes). You should see:
```
[INFO] [talledLocalContainer] Server startup in [XXX] ms
[INFO] Started jira-docusign-plugin in embedded container
```

---

### **Step 2: Access Jira and Create/Open an Issue**

1. **Open Browser**: Navigate to `http://localhost:2990/jira`
2. **Login**: 
   - Username: `admin`
   - Password: `admin`
3. **Create or Open an Issue**:
   - Click "Create" button (or open existing issue like `TEST-1`)
   - The issue should have at least one attachment (optional for OAuth testing)

---

### **Step 3: Locate the DocuSign Panel**

1. **Navigate to Issue View**: Open any Jira issue
2. **Find the Panel**: Look in the **right sidebar** of the issue view page
3. **Panel Title**: You should see "DocuSign Integration" heading

**What You Should See Initially:**

The panel will show one of two states:

**State A: Not Connected** (First Time)
```
┌─────────────────────────────┐
│ DocuSign Integration        │
├─────────────────────────────┤
│ Connect to DocuSign         │
│ You need to connect your    │
│ DocuSign account...         │
│                             │
│ [🔗 Connect DocuSign]       │ ← Click here!
└─────────────────────────────┘
```

**State B: Already Connected**
```
┌─────────────────────────────┐
│ DocuSign Integration        │
├─────────────────────────────┤
│ ✅ DocuSign Connected       │
│ You are successfully        │
│ connected to DocuSign...    │
└─────────────────────────────┘
```

---

### **Step 4: Click "Connect DocuSign" Button**

1. **Click the Button**: Click the "Connect DocuSign" button in the panel
2. **Expected Behavior**: You should be **immediately redirected** to DocuSign

**Expected URL Redirect:**

```
http://localhost:2990/jira/plugins/servlet/docusign/connect
```

This triggers:
- PKCE code_verifier generation
- Code_verifier stored in session
- Redirect to DocuSign authorization URL

**Redirect URL Pattern** (you may see this briefly in browser address bar):
```
https://account-d.docusign.com/oauth/auth?
  response_type=code
  &client_id=3aaf241e-0f12-40f9-b3e7-d7e96d5026d6
  &redirect_uri=http://localhost:2990/jira/plugins/servlet/docusign/callback
  &scope=signature
  &code_challenge=<base64url-encoded-hash>
  &code_challenge_method=S256
```

---

### **Step 5: DocuSign Authorization Page**

**What You Should See:**

1. **DocuSign Login Page** (if not already logged in):
   ```
   ┌─────────────────────────────────┐
   │ DocuSign                        │
   ├─────────────────────────────────┤
   │ Email: [_____________]          │
   │ Password: [_____________]       │
   │                                 │
   │ [Sign In]                       │
   └─────────────────────────────────┘
   ```

2. **After Login - Consent Page**:
   ```
   ┌─────────────────────────────────┐
   │ Authorize Application           │
   ├─────────────────────────────────┤
   │ Jira DocuSign Plugin wants to: │
   │                                 │
   │ ✓ Send documents for signature │
   │                                 │
   │ [Cancel]  [Accept]              │ ← Click Accept!
   └─────────────────────────────────┘
   ```

3. **Click "Accept"**: Authorize the Jira plugin to access DocuSign

---

### **Step 6: OAuth Callback and Token Exchange**

**What Should Happen:**

After clicking "Accept" on DocuSign:

1. **Automatic Redirect**: DocuSign redirects you back to Jira:
   ```
   http://localhost:2990/jira/plugins/servlet/docusign/callback?code=XXXXX
   ```

2. **Backend Processing** (happens automatically):
   - Callback servlet receives authorization code
   - Retrieves code_verifier from session
   - Exchanges code for access token via DocuSign API
   - Stores token in memory (keyed by session ID)

3. **Success Page Displayed**:
   ```
   ┌─────────────────────────────────┐
   │ ✅ DocuSign OAuth Success       │
   ├─────────────────────────────────┤
   │ Successfully connected to       │
   │ DocuSign!                       │
   │                                 │
   │ Access Token: abc12345...xyz9   │
   │ Expires In: 3600 seconds        │
   │                                 │
   │ [Return to Jira]                │
   └─────────────────────────────────┘
   ```

---

### **Step 7: Verify Connection Status**

1. **Return to Issue View**: Click "Return to Jira" or navigate back to an issue
2. **Check Panel Status**: The DocuSign panel should now show:

```
┌─────────────────────────────┐
│ DocuSign Integration        │
├─────────────────────────────┤
│ ✅ DocuSign Connected       │
│ You are successfully        │
│ connected to DocuSign. You  │
│ can now send documents for  │
│ signing.                    │
└─────────────────────────────┘
```

**✅ SUCCESS INDICATORS:**

- ✅ Green success message with checkmark icon
- ✅ "DocuSign Connected" text
- ✅ No "Connect DocuSign" button visible
- ✅ You can proceed to send documents

---

## 🔍 **Verification Checklist**

After completing the OAuth flow, verify:

- [ ] Redirected to DocuSign login/consent page
- [ ] Successfully logged into DocuSign
- [ ] Clicked "Accept" on consent page
- [ ] Redirected back to Jira callback URL
- [ ] Success page displayed with token info
- [ ] Panel shows "DocuSign Connected" status
- [ ] Token persists when refreshing issue page

---

## ❌ **Common OAuth Errors and Solutions**

### **Error 1: "Authorization code not found in callback"**

**Symptom:**
```
DocuSign OAuth Error
Authorization code not found in callback.
```

**Cause:**
- DocuSign didn't return `code` parameter in callback URL
- User denied authorization on DocuSign

**Solution:**
- Click "Accept" on DocuSign consent page (not "Cancel")
- Check browser address bar for `?code=XXX` parameter
- Try the flow again from the beginning

---

### **Error 2: "Code verifier not found in session"**

**Symptom:**
```
DocuSign OAuth Error
Code verifier not found in session. Please try again.
```

**Cause:**
- Session expired between connect and callback
- Browser cookies/session storage disabled
- Multiple browser tabs interfered

**Solution:**
- Ensure cookies are enabled in browser
- Don't close browser between connect and callback
- Complete the flow in a single browser session
- Try the flow again from the beginning

---

### **Error 3: "HTTP 400 from DocuSign token endpoint"**

**Symptom:**
```
Failed to exchange authorization code for token: 
HTTP 400 from DocuSign token endpoint: {"error":"invalid_grant"}
```

**Cause:**
- Authorization code expired (codes expire quickly, ~10 seconds)
- Code verifier doesn't match code challenge
- Code already used (can only be used once)

**Solution:**
- Complete the flow quickly (don't wait between steps)
- Ensure PKCE implementation is correct (check code_challenge_method=S256)
- Try the flow again (generate new code)

---

### **Error 4: "Missing config: DOCUSIGN_CLIENT_ID"**

**Symptom:**
```
DocuSign OAuth Error
Failed to initiate OAuth login: 
Missing config: DOCUSIGN_CLIENT_ID (set env var or -DDOCUSIGN_CLIENT_ID)
```

**Cause:**
- Environment variables not set before starting Jira
- `setenv.sh` not sourced

**Solution:**
```bash
# Stop Jira (Ctrl+C)
# Set environment variables
source setenv.sh

# Verify variables are set
echo $DOCUSIGN_CLIENT_ID

# Restart Jira
atlas-run
```

---

### **Error 5: "invalid_client" from DocuSign**

**Symptom:**
```
DocuSign redirect shows error: "invalid_client"
```

**Cause:**
- Client ID (Integration Key) is incorrect
- Client ID not found in DocuSign account
- Using production client ID with demo environment (or vice versa)

**Solution:**
- Verify `DOCUSIGN_CLIENT_ID` matches Integration Key in DocuSign Admin
- Ensure using demo environment (`account-d.docusign.com`) for demo Integration Key
- Check `setenv.sh` has correct client ID

---

### **Error 6: "There are no redirect URIs registered"**

**Symptom:**
```
DocuSign error: "There are no redirect URIs registered with DocuSign"
```

**Cause:**
- Redirect URI not configured in DocuSign Admin
- Redirect URI doesn't match exactly

**Solution:**
1. Go to DocuSign Admin: https://admindemo.docusign.com
2. Navigate to: **Apps & Keys** → Your Integration → **Settings**
3. Add Redirect URI: `http://localhost:2990/jira/plugins/servlet/docusign/callback`
4. **Important**: URI must match exactly (including `http://` not `https://`)

---

### **Error 7: Connection Status Always Shows "Not Connected"**

**Symptom:**
- Panel always shows "Connect DocuSign" button
- Even after successful OAuth flow

**Cause:**
- Token not stored correctly
- Session ID mismatch between connect and status check
- Token expired

**Solution:**
- Verify callback servlet stored token (check success page shows token)
- Check browser session (don't use incognito/private mode)
- Refresh issue page
- Check REST endpoint: `GET /rest/docusign/1.0/send/status`

---

## 🧪 **Manual API Testing**

### **Test Connection Status Endpoint**

```bash
# Get session cookie first (login to Jira in browser, copy cookie)
# Or use Basic Auth:
curl -u admin:admin \
  http://localhost:2990/jira/rest/docusign/1.0/send/status
```

**Expected Response (Not Connected):**
```json
{"connected": false}
```

**Expected Response (Connected):**
```json
{"connected": true, "expiresAt": 1234567890000}
```

---

### **Test Connect Servlet Directly**

```bash
# Visit in browser (while logged into Jira):
http://localhost:2990/jira/plugins/servlet/docusign/connect
```

**Expected:** Immediate redirect to DocuSign

---

### **Verify Token Storage**

After successful OAuth:

1. **Check Jira Logs**:
   ```bash
   tail -f target/jira-home/log/atlassian-jira.log | grep -i docusign
   ```

2. **Look for**:
   - "Successfully exchanged code for token"
   - Token storage confirmation

---

## 🔐 **Security Notes**

- **PKCE Protection**: Code verifier stored in server-side session (not client-side)
- **Token Storage**: Currently in-memory (will be cleared on Jira restart)
- **Session Scope**: Tokens are per-session (different browser = different token)
- **Expiration**: Tokens expire (usually 1 hour), user must re-authenticate

---

## 📝 **Testing Workflow Summary**

```
1. Start Jira with env vars → source setenv.sh && atlas-run
2. Login to Jira → http://localhost:2990/jira (admin/admin)
3. Open issue → See DocuSign panel in sidebar
4. Click "Connect DocuSign" → Redirects to DocuSign
5. Login to DocuSign → Enter credentials
6. Accept authorization → Click "Accept"
7. Redirected back → See success page
8. Return to issue → Panel shows "Connected" ✅
```

---

## 🎯 **Success Criteria**

✅ **OAuth Flow Complete When:**
- Panel shows "DocuSign Connected" status
- No "Connect DocuSign" button visible
- Success page displayed after callback
- Token stored and accessible
- Status endpoint returns `{"connected": true}`

---

## 🐛 **Debugging Tips**

1. **Check Browser Console** (F12):
   - Look for JavaScript errors
   - Check AJAX requests to `/rest/docusign/1.0/send/status`
   - Verify redirects are happening

2. **Check Network Tab**:
   - Verify redirect to DocuSign includes all parameters
   - Check callback URL receives `code` parameter
   - Verify token exchange request succeeds

3. **Check Jira Logs**:
   ```bash
   tail -f target/jira-home/log/atlassian-jira.log
   ```

4. **Verify Environment Variables**:
   ```bash
   echo $DOCUSIGN_CLIENT_ID
   echo $DOCUSIGN_ACCOUNT_ID
   ```

---

## 📚 **Additional Resources**

- DocuSign OAuth 2.0 Guide: https://developers.docusign.com/platform/auth/oauth2/
- PKCE Specification: RFC 7636
- Jira Plugin Documentation: https://developer.atlassian.com/jiradev/

---

**Last Updated**: After OAuth implementation with PKCE

