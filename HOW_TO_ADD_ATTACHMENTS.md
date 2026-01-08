# How to Add Attachments to Jira Issues

## 📎 **Method 1: Via Jira Web Interface (Easiest)**

### **Step 1: Access Jira**
1. Open your browser
2. Navigate to: `http://localhost:2990/jira`
3. Login with:
   - **Username:** `admin`
   - **Password:** `admin`

### **Step 2: Create or Open an Issue**
1. Click **"Create"** button (top right) or
2. Navigate to an existing issue

### **Step 3: Add Attachment**
1. On the issue page, look for the **"Attachments"** section
2. Click **"Attach files"** or drag and drop files
3. Select a file (PDF, DOCX, etc.) from your computer
4. Click **"Attach"** or **"Upload"**
5. Wait for upload to complete

### **Step 4: Verify Attachment**
- The attachment should appear in the Attachments section
- Note the attachment filename (you'll need this for testing)

---

## 📎 **Method 2: Via REST API (For Testing)**

### **Using cURL:**
```bash
# First, get a session token (or use Basic Auth)
curl -u admin:admin \
  -X POST \
  -F "file=@/path/to/your/document.pdf" \
  -F "comment=Test attachment" \
  http://localhost:2990/jira/rest/api/2/issue/TEST-1/attachments
```

### **Using Browser Dev Tools:**
1. Open browser console (F12)
2. Navigate to an issue page
3. Use the Jira REST API to upload

---

## 📎 **Method 3: Create Test Issue with Attachment**

### **Quick Test Script:**
```bash
# 1. Create a test issue
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "fields": {
      "project": {"key": "TEST"},
      "summary": "Test Issue for DocuSign",
      "issuetype": {"name": "Task"}
    }
  }' \
  http://localhost:2990/jira/rest/api/2/issue

# 2. Note the issue key from response (e.g., TEST-1)

# 3. Upload attachment to the issue
curl -u admin:admin \
  -X POST \
  -F "file=@/path/to/test.pdf" \
  http://localhost:2990/jira/rest/api/2/issue/TEST-1/attachments
```

---

## 🔍 **Finding Attachment IDs**

### **Method 1: Via REST API**
```bash
# Get all attachments for an issue
curl -u admin:admin \
  http://localhost:2990/jira/rest/api/2/issue/TEST-1?fields=attachment

# Response will include attachment IDs:
# "attachments": [{"id": 12345, "filename": "test.pdf", ...}]
```

### **Method 2: Via Browser Dev Tools**
1. Open issue page in browser
2. Press F12 to open Dev Tools
3. Go to Network tab
4. Refresh the page
5. Look for API calls that return attachment data
6. Find the `id` field in the response

### **Method 3: Check Issue JSON**
```bash
curl -u admin:admin \
  http://localhost:2990/jira/rest/api/2/issue/TEST-1 \
  | jq '.fields.attachment[] | {id: .id, filename: .filename}'
```

---

## 📋 **Testing the DocuSign Plugin with Attachments**

### **Step 1: Create Issue with Attachment**
1. Create issue: `TEST-1`
2. Upload a PDF attachment
3. Note the attachment ID (e.g., `12345`)

### **Step 2: Test via UI**
1. View the issue page
2. Find the **DocuSign panel** on the right side
3. You should see your attachment listed with a checkbox
4. Select the attachment
5. Add signers
6. Click "Send to DocuSign"

### **Step 3: Test via REST API**
```bash
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "issueKey": "TEST-1",
    "attachmentIds": [12345],
    "signers": [
      {
        "name": "John Doe",
        "email": "john@example.com",
        "routingOrder": "1"
      }
    ]
  }' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

---

## 📝 **Sample Test Files**

You can create simple test files:

### **Create a Test PDF (macOS):**
```bash
# Create a simple text file
echo "This is a test document for DocuSign" > test.txt

# Convert to PDF (if you have textutil)
textutil -convert pdf test.txt -output test.pdf
```

### **Or Download a Sample PDF:**
- Use any PDF document from your computer
- Or create one using online tools

---

## ✅ **Quick Checklist**

- [ ] Jira is running at http://localhost:2990/jira
- [ ] Logged in as admin/admin
- [ ] Created or opened an issue
- [ ] Uploaded at least one attachment (PDF recommended)
- [ ] Noted the attachment ID
- [ ] DocuSign panel appears on issue page
- [ ] Attachment is visible in the panel

---

## 🎯 **Troubleshooting**

### **Attachments Not Showing in Panel**
1. Check that the issue has attachments
2. Verify the context provider is working
3. Check browser console for JavaScript errors
4. Refresh the page

### **Can't Upload Attachments**
1. Check file size limits
2. Verify file format is supported
3. Check Jira permissions
4. Try a different file

### **Attachment IDs Not Found**
1. Use REST API to get attachment IDs
2. Check the issue JSON response
3. Verify the attachment actually exists

---

**Need Help?** Check the browser console (F12) for any errors when viewing the issue page.

