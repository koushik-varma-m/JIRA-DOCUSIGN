# How to Start Jira Properly

## ⚠️ Problem Identified

Jira may fail to start if Tomcat ends up with a partially-exploded `jira` webapp directory (missing JARs).
Also, `atlas-run` is easiest to run in the foreground during development.

## ✅ Solution: Run Jira in Foreground

### Option 1: Run in Current Terminal (Recommended for Testing)
```bash
cd /Users/koushikvarma/jira-docusign-plugin
atlas-run
```

**Keep this terminal open** - Jira will keep running. Press `Ctrl+C` to stop.

If startup fails with missing JARs (example: `batik-xml-1.17.jar`), run:
```bash
rm -rf target/container/tomcat9x/cargo-jira-home/webapps/jira
atlas-run
```

Shortcut script:
```bash
./scripts/jira-start-clean.sh
```

---

### Option 2: Run in Background Properly (for Long-Running)
```bash
cd /Users/koushikvarma/jira-docusign-plugin
nohup atlas-run > jira.log 2>&1 &
echo "Jira starting... PID: $!"
```

Then check status:
```bash
tail -f jira.log
```

To stop:
```bash
./scripts/jira-stop.sh
```

---

### Option 3: Use screen/tmux (Best for Development)
```bash
# Start a screen session
screen -S jira

# Inside screen, start Jira
cd /Users/koushikvarma/jira-docusign-plugin
atlas-run

# Detach: Press Ctrl+A then D
# Reattach: screen -r jira
```

Shortcut script (recommended):
```bash
./scripts/jira-start-screen.sh
```

---

## 🔍 How to Verify Jira is Running

1. **Check Status**:
   ```bash
   curl http://localhost:2990/jira/status
   ```
   Should return: `{"state":"RUNNING"}`

2. **Check Process**:
   ```bash
   ps aux | grep atlas-run | grep -v grep
   ```

3. **Check Port**:
   ```bash
   lsof -i :2990
   ```

4. **Open Browser**:
   Open http://localhost:2990/jira

---

## ⏱️ Startup Time

- **First time**: 2-5 minutes
- **Subsequent starts**: 1-3 minutes
- **Look for**: "JIRA has started" or "Startup is complete" in logs

---

## 🧪 After Jira Starts

1. Wait for "JIRA has started" message
2. Test endpoint:
   ```bash
   curl -u admin:admin \
     -X POST \
     -H "Content-Type: application/json" \
     -d '{"issueKey":"TEST-1"}' \
     http://localhost:2990/jira/rest/docusign/1.0/send
   ```

---

## ✅ Current Status

- ✅ Code compiles successfully
- ✅ Jira starts with `atlas-run` (after cleaning if needed)
