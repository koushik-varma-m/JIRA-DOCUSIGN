# Debugging "Add Signer" Button Issue

## 🔧 **Fix Applied**

The JavaScript has been updated with multiple binding strategies to ensure the "Add Signer" button works:

1. **Direct Button Binding** - Primary method
2. **Event Delegation** - Backup method  
3. **Delayed Initialization** - 1 second backup
4. **Button Existence Check** - Validates button exists before binding
5. **stopPropagation()** - Prevents event bubbling issues

---

## 🧪 **Manual Testing in Browser Console**

If the button still doesn't work, try these tests in the browser console (F12):

### **Test 1: Check if button exists**
```javascript
jQuery('#docusign-add-signer').length
// Should return 1 if button exists, 0 if not
```

### **Test 2: Check if jQuery is available**
```javascript
typeof jQuery
// Should return "function"
```

### **Test 3: Check if AJS.$ is available**
```javascript
typeof AJS.$
// Should return "function"
```

### **Test 4: Manually trigger click**
```javascript
jQuery('#docusign-add-signer').click();
// OR
AJS.$('#docusign-add-signer').click();
```

### **Test 5: Check if function exists**
```javascript
// Open console, then try:
jQuery('#docusign-add-signer').on('click', function() {
    alert('Button clicked!');
    console.log('Button works!');
});
```

### **Test 6: Check if signers list exists**
```javascript
jQuery('#docusign-signers-list').length
// Should return 1
```

---

## 🔍 **Check Browser Console for Errors**

Look for errors like:
- `jira-docusign-plugin.js:XX Error...`
- `Uncaught TypeError: Cannot read property...`
- `$ is not defined`
- `jQuery is not defined`

---

## ✅ **Verify Plugin Files Are Loading**

1. Open browser DevTools (F12)
2. Go to Network tab
3. Filter by "docusign"
4. Refresh the page
5. Check if these files load:
   - `jira-docusign-plugin.js` - Status should be 200
   - `jira-docusign-plugin.css` - Status should be 200

---

## 🎯 **Alternative: Inline JavaScript Test**

If the external JS file isn't working, we could try adding inline JavaScript directly in the Velocity template as a test.

---

## 📋 **Current Code Structure**

```javascript
(function($) {
    'use strict';
    
    var signerCounter = 0;
    
    function addSignerRow() {
        // Creates signer row HTML
    }
    
    function initializeDocuSignPlugin() {
        // Checks for panel and button
        // Binds event handlers
    }
    
    $(document).ready(function() {
        initializeDocuSignPlugin();
    });
    
    // Backup initialization after 1 second
    setTimeout(function() {
        // Additional binding attempt
    }, 1000);
    
})(AJS.$ || jQuery);
```

---

## ✅ **Quick Verification Steps**

1. **Hard refresh browser** (Ctrl+F5 or Cmd+Shift+R)
2. **Check if panel is visible** - If yes, plugin loaded
3. **Open console** (F12) and check for errors
4. **Test button click manually** in console
5. **Check Network tab** to verify JS file loads

If these don't work, we may need to check:
- Is the JavaScript file actually being served?
- Is jQuery/AJS available when our code runs?
- Are there any JavaScript errors blocking execution?

