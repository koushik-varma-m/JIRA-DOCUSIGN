# Class Roles and Removal Guide

## 🔍 **Classes Created for JWT Testing (Can Be Removed After Verification)**

### **1. `DocusignJwtTestRunner`**
**Location:** `src/main/java/com/koushik/docusign/DocusignJwtTestRunner.java`  
**Purpose:** Temporary test class with `main()` method to verify JWT authentication works  
**Status:** ✅ **SAFE TO REMOVE** after confirming JWT authentication works

**Why it can be removed:**
- Only contains a `main()` method for manual testing
- Not used by the Jira plugin runtime
- Testing can be done via REST endpoint instead
- Keeps production code clean

**How to verify before removing:**
1. Run it once: `mvn exec:java -Dexec.mainClass="com.koushik.docusign.DocusignJwtTestRunner"`
2. Confirm it prints a valid access token and expiration time
3. Then delete the file

---

## ✅ **Classes for Production Use (Keep These)**

### **1. `DocusignJwtService`**
**Location:** `src/main/java/com/koushik/docusign/docusign/DocusignJwtService.java`  
**Purpose:** Handles JWT authentication with DocuSign  
**Status:** ✅ **KEEP - Production Class**

**Why it should remain:**
- Used by `DocusignRestResource` for authentication
- Core business logic for obtaining access tokens
- No `main()` method - pure service class
- Required for all DocuSign API calls

---

### **2. `DocusignService`** (Existing)
**Location:** `src/main/java/com/koushik/docusign/docusign/DocusignService.java`  
**Purpose:** Handles DocuSign envelope creation using REST API  
**Status:** ✅ **KEEP - Production Class**

**Note:** If you switch to DocuSign Java SDK, you may replace this with SDK-based classes, but keep the interface consistent.

---

### **3. `DocusignRestResource`**
**Location:** `src/main/java/com/koushik/docusign/rest/DocusignRestResource.java`  
**Purpose:** REST endpoint for Jira plugin  
**Status:** ✅ **KEEP - Production Class**

**Why it should remain:**
- Jira REST API endpoint (`/rest/docusign/1.0/send`)
- Used by frontend JavaScript to send documents
- Required for plugin functionality

---

### **4. `DocusignContextProvider`**
**Location:** `src/main/java/com/koushik/docusign/DocusignContextProvider.java`  
**Purpose:** Provides context data (issueKey, attachments) to Velocity template  
**Status:** ✅ **KEEP - Production Class**

**Why it should remain:**
- Required for web panel rendering
- Provides data to frontend UI
- Part of plugin's UI functionality

---

### **5. ApiClient Factory (If Created)**
**Location:** `src/main/java/com/koushik/docusign/docusign/DocusignApiClientFactory.java` (or similar)  
**Purpose:** Creates configured DocuSign ApiClient instances  
**Status:** ✅ **KEEP - Production Class**

**Why it should remain:**
- Reusable factory for DocuSign SDK client creation
- Centralizes ApiClient configuration
- Used by production REST endpoints

---

## 📋 **Summary Table**

| Class Name | Location | Type | Keep/Remove | Reason |
|------------|----------|------|-------------|--------|
| `DocusignJwtTestRunner` | `src/main/java/...` | Test-only | ❌ **REMOVE** | Only for manual testing |
| `DocusignJwtService` | `src/main/java/...` | Production | ✅ **KEEP** | Used by REST endpoints |
| `DocusignService` | `src/main/java/...` | Production | ✅ **KEEP** | Core DocuSign logic |
| `DocusignRestResource` | `src/main/java/...` | Production | ✅ **KEEP** | REST API endpoint |
| `DocusignContextProvider` | `src/main/java/...` | Production | ✅ **KEEP** | Web panel context |
| `ApiClientFactory` | `src/main/java/...` | Production | ✅ **KEEP** | Reusable factory |

---

## 🧹 **Cleanup Steps After JWT Verification**

1. **Verify JWT works:**
   ```bash
   # Test via REST endpoint or test runner
   curl -X POST http://localhost:2990/jira/rest/docusign/1.0/send \
     -H "Content-Type: application/json" \
     -d '{"issueKey":"TEST-1","attachmentIds":[1],"signers":[...]}'
   ```

2. **Delete test-only class:**
   ```bash
   rm src/main/java/com/koushik/docusign/DocusignJwtTestRunner.java
   ```

3. **Rebuild:**
   ```bash
   atlas-compile
   ```

---

## ⚠️ **Important Notes**

- **Never remove production classes** that are used by REST endpoints or web panels
- **Test classes with `main()` methods** should be in `src/test/java/` or removed after verification
- **Keep service classes** that handle business logic (authentication, API calls)
- **Keep factory classes** that create reusable instances

---

## 🔍 **How to Verify a Class is Test-Only**

A class is likely test-only if it:
- Contains `public static void main(String[] args)`
- Only prints/logs results, doesn't return values used by other classes
- Has "Test" or "Runner" in the name
- Not imported/used by any production classes

A class is production if it:
- Is injected into REST resources or other services
- Has methods called by production code
- Returns values used by other classes
- Part of the plugin's runtime functionality




