# Jira Access Information

## 🌐 **Access URL**

**Main Jira Interface:**
```
http://localhost:2990/jira
```

---

## 🔐 **Default Login Credentials**

**Username:** `admin`  
**Password:** `admin`

---

## 📋 **Quick Access Links**

### **1. Main Jira Dashboard**
```
http://localhost:2990/jira
```
- Main Jira interface
- Create issues
- Upload attachments
- Browse projects

### **2. Jira Status Check**
```
http://localhost:2990/jira/status
```
- Check if Jira is running
- Returns: `{"state":"RUNNING"}`

### **3. DocuSign REST API Endpoint**
```
http://localhost:2990/jira/rest/docusign/1.0/send
```
- POST endpoint for sending documents to DocuSign
- Requires authentication (Basic Auth)
- Accepts JSON payload

### **4. Plugin Management**
```
http://localhost:2990/jira/plugins/servlet/upm/manage/all
```
- View installed plugins
- Check plugin status
- Enable/disable plugins

### **5. REST API Browser**
```
http://localhost:2990/jira/rest/api/2
```
- Browse available REST APIs
- View API documentation
- Test endpoints

---

## 🚀 **First Time Access**

### **Step 1: Open Browser**
Navigate to: `http://localhost:2990/jira`

### **Step 2: Setup Wizard (If First Time)**
If this is the first time accessing Jira, you'll see a setup wizard:
1. **Choose Language** - Select your preferred language
2. **Application Title** - Enter a title for your Jira instance
3. **License Agreement** - Accept the license (if required)
4. **Administrator Account** - Create admin account (usually pre-configured)
5. **Email Configuration** - Can skip for local development
6. **Features** - Choose features to enable
7. **Complete Setup** - Finish wizard

### **Step 3: Login**
After setup (or if already configured):
- **Username:** `admin`
- **Password:** `admin`

---

## 🧪 **Testing the DocuSign Plugin**

### **1. Create Test Issue**
1. Login to Jira
2. Click "Create" button
3. Create issue: `TEST-1`
4. Upload a PDF attachment
5. Save issue

### **2. Test REST Endpoint**

#### **Using cURL:**
```bash
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "issueKey": "TEST-1",
    "signers": [
      {"name": "John Doe", "email": "john@example.com", "order": "1"}
    ]
  }' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

#### **Using Browser (JavaScript Console):**
```javascript
fetch('http://localhost:2990/jira/rest/docusign/1.0/send', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': 'Basic ' + btoa('admin:admin')
  },
  body: JSON.stringify({
    issueKey: 'TEST-1',
    signers: [{
      name: 'John Doe',
      email: 'john@example.com',
      order: '1'
    }]
  })
})
.then(r => r.json())
.then(console.log)
.catch(console.error);
```

#### **Using Postman:**
- **Method:** POST
- **URL:** `http://localhost:2990/jira/rest/docusign/1.0/send`
- **Auth:** Basic Auth (username: `admin`, password: `admin`)
- **Headers:** `Content-Type: application/json`
- **Body (JSON):**
  ```json
  {
    "issueKey": "TEST-1",
    "signers": [
      {"name": "John Doe", "email": "john@example.com", "order": "1"}
    ]
  }
  ```

---

## 🔍 **Verify Jira is Running**

### **Check Status:**
```bash
curl http://localhost:2990/jira/status
```
Should return: `{"state":"RUNNING"}`

### **Check in Browser:**
Simply navigate to: `http://localhost:2990/jira`

---

## ⚠️ **Troubleshooting**

### **Cannot Access Jira**
1. **Check if Jira is running:**
   ```bash
   curl http://localhost:2990/jira/status
   ```

2. **Check if port 2990 is in use:**
   ```bash
   lsof -i :2990
   ```

3. **Start Jira if not running:**
   ```bash
   cd /Users/koushikvarma/jira-docusign-plugin
   screen -dmS jira-test bash -c 'atlas-run > jira-screen.log 2>&1'
   ```

4. **Wait for startup:**
   - Jira takes 60-90 seconds to start
   - Check logs: `tail -f jira-screen.log`

### **Login Issues**
- Default credentials: `admin` / `admin`
- If login fails, check if setup wizard needs to be completed
- Reset admin password via setup wizard if needed

### **Endpoint Not Found (404)**
1. Verify plugin is loaded:
   - Go to: Administration → Manage apps → Manage apps
   - Look for "Jira DocuSign Plugin"
   - Status should be "Enabled"

2. Check endpoint URL:
   - Correct: `/rest/docusign/1.0/send`
   - Wrong: `/rest/docusign/1.0/send/` (trailing slash)

---

## 📊 **Current Status**

**Last Checked:** Based on latest testing  
**Jira Status:** ✅ RUNNING  
**Endpoint Status:** ✅ WORKING  
**Plugin Status:** ✅ LOADED

---

**Access your Jira instance at: http://localhost:2990/jira** 🚀

