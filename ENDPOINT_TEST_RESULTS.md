# Endpoint Test Results

## Test Status

**Date**: Current testing session
**Jira Status**: ✅ Running on port 2990
**Endpoint**: `POST /rest/docusign/1.0/send`

## Test Results

### Connection Status
- ✅ Jira is accessible: `http://localhost:2990/jira/status` returns RUNNING
- ⚠️ Endpoint connection: Timeout/reset observed

### Possible Issues

1. **Plugin Not Fully Loaded**
   - Plugin may still be initializing
   - First load can take several minutes
   - Need to verify plugin is registered

2. **Endpoint Registration**
   - REST endpoint may need more time to register
   - Could be OSGi bundle activation delay

3. **Issue Doesn't Exist**
   - TEST-1 issue may not exist yet
   - Should return 400 with "Invalid issue key" error if endpoint works

## Recommendations

### Manual Testing via Browser
1. Open http://localhost:2990/jira
2. Log in as admin/admin
3. Create a test issue (e.g., TEST-1)
4. Upload a PDF attachment
5. Test endpoint via REST client or curl

### Check Plugin Status
```bash
# Check if plugin is loaded
curl -u admin:admin "http://localhost:2990/jira/rest/plugins/1.0/" | grep -i docusign

# Or via Jira UI:
# Administration → Manage apps → Manage apps
# Look for "Jira DocuSign Plugin"
```

### Test with Valid Issue
```bash
# First create issue TEST-1 with attachment via UI, then:
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"issueKey":"TEST-1"}' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

## Expected Responses

### Success Case
```json
{"envelopeId":"xxxx-xxxx-xxxx-xxxx"}
```

### Error Cases
```json
{"error":"Invalid issue key"}
{"error":"Issue has no attachments"}
{"error":"Failed to get access token: consent_required"}
{"error":"Failed to create envelope: ..."}
```

## Next Steps

1. ✅ Verify plugin is loaded in Jira UI
2. ✅ Create test issue with attachment
3. ✅ Retry endpoint test
4. ✅ Check logs for detailed errors if any

