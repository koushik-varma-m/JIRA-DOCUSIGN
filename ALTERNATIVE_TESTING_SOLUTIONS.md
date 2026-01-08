# Alternative Solutions & Problem Analysis

## 🔍 **Potential Problems Identified**

### **Problem #1: Jira Must Run in Foreground**
**Issue:** `atlas-run` is designed as an interactive development server. When run in background, it starts but then shuts down automatically.

**Solutions:**

#### **Solution A: Use `screen` or `tmux` (Recommended)**
```bash
# Install screen if not available
# macOS: already installed
# Linux: sudo apt-get install screen

# Start a screen session
screen -S jira

# Inside screen, start Jira
cd /Users/koushikvarma/jira-docusign-plugin
atlas-run

# Detach: Press Ctrl+A then D
# Reattach: screen -r jira
```

#### **Solution B: Use `nohup` with proper logging**
```bash
cd /Users/koushikvarma/jira-docusign-plugin
nohup atlas-run > jira.log 2>&1 &

# Check if running
ps aux | grep atlas-run

# Monitor logs
tail -f jira.log

# To stop
pkill -f atlas-run
```

#### **Solution C: Use Docker (Advanced)**
Create a Docker container to run Jira persistently.

---

### **Problem #2: REST Endpoint Not Being Registered**

**Potential Causes:**
1. REST resource not properly scanned
2. Missing annotations
3. Plugin not fully loaded
4. OSGi bundle activation delay

**Solutions:**

#### **Check 1: Verify Plugin is Loaded**
```bash
# After Jira starts, check logs
tail -f target/jira/home/log/atlassian-jira.log | grep -i docusign

# Should see:
# Spring context started for bundle: com.koushik.docusign.jira-docusign-plugin
```

#### **Check 2: Verify REST Endpoint Registration**
```bash
# Check if endpoint is registered
curl -u admin:admin http://localhost:2990/jira/rest/plugins/1.0/ | grep -i docusign

# Or check via Jira UI:
# Administration → System → REST API → Browse all endpoints
# Look for: /rest/docusign/1.0/send
```

#### **Fix 1: Ensure Proper REST Resource Configuration**
The `atlassian-plugin.xml` should have:
```xml
<rest key="docusign-rest" path="/docusign" version="1.0">
    <description>DocuSign REST API</description>
    <resource key="docusign-resource"
            name="DocuSign Resource"
            path="/send"
            type="com.koushik.docusign.rest.DocusignRestResource"/>
</rest>
```

**Current status:** ✅ Already correct

---

### **Problem #3: Dependency Issues**

**Check:** Are all dependencies properly embedded?

**Current dependencies:**
- ✅ Apache HttpClient (embedded)
- ✅ Gson (embedded)
- ✅ Jira API (provided)
- ✅ javax.ws.rs (provided by Jira)

**Verify:**
```bash
# Check what's in the plugin JAR
jar -tf target/jira-docusign-plugin-1.0.0-SNAPSHOT.jar | grep -E "(httpclient|gson)" | head -5
```

---

### **Problem #4: OSGi Bundle Issues**

**Check logs for:**
```
FAILED PLUGIN: com.koushik.docusign.jira-docusign-plugin
BundleException
osgi.wiring.package
```

**Fix:** Already handled in `pom.xml` with proper `Import-Package` and `Embed-Dependency` configuration.

---

## 🚀 **Recommended Approach: Complete Testing Strategy**

### **Step 1: Start Jira Properly Using Screen**

```bash
# Terminal 1: Start screen session
screen -S jira-test

# Inside screen:
cd /Users/koushikvarma/jira-docusign-plugin
atlas-run

# Wait for: "jira started successfully"
# Detach: Ctrl+A, then D
```

### **Step 2: Test from Another Terminal**

```bash
# Terminal 2: Test endpoint
curl -v -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "issueKey": "INVALID-999",
    "signers": [
      {"name": "Test User", "email": "test@test.com", "order": "1"}
    ]
  }' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

### **Step 3: Verify Plugin Loaded**

```bash
# Check plugin loaded
tail -1000 target/jira/home/log/atlassian-jira.log | grep -i "Spring context.*docusign"

# Should see plugin loaded message
```

### **Step 4: Create Test Issue and Test Full Flow**

1. Open: http://localhost:2990/jira
2. Login: admin/admin
3. Create issue: TEST-1
4. Upload attachment: Any PDF
5. Test endpoint with valid issue

---

## 🔧 **Alternative: Manual Plugin Installation**

If `atlas-run` continues to be problematic, you can:

1. **Build plugin JAR:**
   ```bash
   atlas-package
   ```

2. **Install in existing Jira:**
   - Copy `target/jira-docusign-plugin-1.0.0-SNAPSHOT.jar` to Jira's plugin directory
   - Restart Jira
   - Enable plugin in Jira UI

---

## 📋 **Checklist: What to Verify**

- [ ] Plugin JAR builds successfully
- [ ] Plugin loads without OSGi errors
- [ ] REST endpoint is registered
- [ ] Endpoint responds (even with errors)
- [ ] Validation errors work (400 responses)
- [ ] DocuSign integration works (when credentials set)

---

## 🐛 **Debugging Steps**

### **If Endpoint Returns 404:**

1. **Check plugin loaded:**
   ```bash
   grep -i "Spring context.*docusign" target/jira/home/log/atlassian-jira.log
   ```

2. **Check REST resource registration:**
   ```bash
   grep -i "docusign" target/jira/home/log/atlassian-jira.log | grep -i "REST\|endpoint\|resource"
   ```

3. **Verify URL:**
   - Should be: `/rest/docusign/1.0/send`
   - Not: `/rest/docusign/1.0/send/`
   - Not: `/rest/docusign/send`

### **If Endpoint Returns 500:**

1. **Check logs for exceptions:**
   ```bash
   tail -500 target/jira/home/log/atlassian-jira.log | grep -iE "ERROR|Exception" | tail -20
   ```

2. **Check for missing configuration:**
   - DocuSign environment variables
   - Missing dependencies

### **If Plugin Doesn't Load:**

1. **Check OSGi errors:**
   ```bash
   grep -iE "FAILED.*plugin|BundleException" target/jira/home/log/atlassian-jira.log
   ```

2. **Verify JAR exists:**
   ```bash
   ls -la target/jira/home/plugins/installed-plugins/jira-docusign-plugin*.jar
   ```

3. **Check dependencies:**
   ```bash
   jar -tf target/jira-docusign-plugin-1.0.0-SNAPSHOT.jar | grep -E "(httpclient|gson)" | head -10
   ```

---

## ✅ **Current Status**

**What's Working:**
- ✅ Code compiles
- ✅ Plugin builds
- ✅ Jira starts
- ✅ No OSGi errors found
- ✅ Configuration is correct

**What Needs Testing:**
- ⏳ Endpoint accessibility (needs foreground Jira)
- ⏳ Full integration test (needs DocuSign credentials)

---

## 🎯 **Recommended Next Steps**

1. **Use screen/tmux to run Jira in background properly**
2. **Test endpoint immediately after startup**
3. **Verify plugin loaded in logs**
4. **Test with valid issue + attachment**
5. **Set DocuSign credentials and test full flow**

The plugin code is correct and ready. The main challenge is keeping Jira running long enough to test, which screen/tmux solves.

