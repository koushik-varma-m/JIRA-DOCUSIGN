# Jira DocuSign Plugin

A Jira plugin that integrates DocuSign eSignature functionality, allowing users to send Jira issue attachments to DocuSign for electronic signing.

## Features

- **REST API Endpoint**: Send documents from Jira issues to DocuSign
- **Attachment Extraction**: Automatically extracts attachments from Jira issues
- **Base64 Encoding**: Converts attachments to Base64 for DocuSign API
- **JWT Authentication**: Secure DocuSign authentication using JWT
- **Envelope Creation**: Creates and sends DocuSign envelopes with documents and signers

## Architecture

This plugin uses direct REST API calls to DocuSign (no SDK) for compatibility with Jira's Java EE (javax.*) runtime environment.

### Key Components

- **DocusignRestResource.java**: REST endpoint handler
- **DocusignService.java**: DocuSign API integration service
- **Direct REST calls**: Uses Apache HttpClient for HTTP communication
- **JWT Authentication**: Uses java-jwt library for token generation

## Prerequisites

- Java 8+
- Maven 3.6+
- Atlassian Plugin SDK (AMPS) 9.1.1+
- Jira 9.12.2+

## Building

```bash
# Clean and compile
atlas-clean
atlas-compile

# Run Jira with plugin
atlas-run
```

Wait 2-3 minutes for Jira to start, then access at http://localhost:2990/jira

## Configuration

### DocuSign Credentials

Edit `src/main/java/com/koushik/docusign/docusign/DocusignService.java`:

```java
private final String integrationKey = "YOUR_INTEGRATION_KEY";
private final String userId = "YOUR_USER_ID";
private final String accountId = "YOUR_ACCOUNT_ID";
private final String privateKey = "YOUR_RSA_PRIVATE_KEY";
```

**Note**: For production, move credentials to configuration properties or environment variables.

### DocuSign Environment

Currently configured for **Demo environment**:
- Base Path: `https://demo.docusign.net/restapi`
- Auth URL: `https://account-d.docusign.com/oauth/token`

For production, change to:
- Base Path: `https://www.docusign.net/restapi`
- Auth URL: `https://account.docusign.com/oauth/token`

## API Usage

### REST Endpoint

**POST** `/rest/docusign/1.0/send`

**Request**:
```json
{
  "issueKey": "TEST-1"
}
```

**Response** (Success):
```json
{
  "envelopeId": "xxxx-xxxx-xxxx-xxxx"
}
```

**Response** (Error):
```json
{
  "error": "Error message here"
}
```

### Example cURL Request

```bash
curl -u admin:admin \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"issueKey":"TEST-1"}' \
  http://localhost:2990/jira/rest/docusign/1.0/send
```

## Setup Requirements

1. **DocuSign Developer Account**: Create an integration and obtain credentials
2. **JWT Consent**: Grant consent for JWT authentication in DocuSign Admin
3. **RSA Key Pair**: Generate RSA key pair for JWT authentication

## Project Structure

```
src/main/java/com/koushik/docusign/
├── rest/
│   └── DocusignRestResource.java    # REST endpoint
├── docusign/
│   └── DocusignService.java         # DocuSign API service
├── DocusignContextProvider.java     # Context provider for UI
└── impl/
    └── MyPluginComponentImpl.java   # Plugin component

src/main/resources/
├── atlassian-plugin.xml              # Plugin manifest
└── templates/
    └── docusign-panel.vm            # UI template
```

## Dependencies

- **Apache HttpClient 4.5.14**: HTTP client for REST calls
- **Gson 2.10.1**: JSON parsing
- **java-jwt 4.4.0**: JWT token generation
- **BouncyCastle 1.70**: RSA key handling

All dependencies are embedded in the OSGi bundle for Jira compatibility.

## Development

### Build Commands

```bash
# Clean build
atlas-clean
atlas-compile

# Package plugin
atlas-package

# Run with debugging
atlas-debug
```

### Testing

1. Start Jira: `atlas-run`
2. Wait for startup (2-3 minutes)
3. Create a test issue with attachments
4. Test endpoint via REST API

## Troubleshooting

### Jira Not Starting
- Ensure port 2990 is available
- Check logs in `target/jira/home/log/`
- Verify Java version (Java 8+ required)

### Plugin Not Loading
- Check for OSGi bundle errors in logs
- Verify all dependencies are embedded
- Run `atlas-clean` and rebuild

### DocuSign Authentication Errors
- Verify credentials are correct
- Check JWT consent is granted in DocuSign Admin
- Ensure RSA private key format is correct

### Endpoint Not Found
- Verify plugin loaded successfully
- Check `atlassian-plugin.xml` REST configuration
- Restart Jira after code changes

## License

[Your License Here]

## Author

Koushik

## Documentation

See the following files for detailed information:
- `CODE_EXPLANATION.md`: Complete codebase explanation
- `ERROR_ANALYSIS_AND_FIXES.md`: Error analysis and fixes
- `ANALYSIS_FROM_WORKING_STATE.md`: Analysis of working state
- `FIXES_APPLIED.md`: Summary of fixes applied
- `START_JIRA.md`: Instructions for starting Jira

