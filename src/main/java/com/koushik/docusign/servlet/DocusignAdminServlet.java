package com.koushik.docusign.servlet;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.koushik.docusign.config.DocusignConfig;
import com.koushik.docusign.security.DocusignCrypto;
import com.koushik.docusign.http.DocusignHttpClientFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Minimal admin configuration UI (server-side rendered) for Jira Data Center friendliness.
 *
 * Path: /plugins/servlet/docusign/admin
 */
public class DocusignAdminServlet extends HttpServlet {

    private static final String CSRF_KEY = "docusign.admin.csrf";
    private static final SecureRandom RAND = new SecureRandom();

    private final JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
    private final PermissionManager permissionManager = ComponentAccessor.getPermissionManager();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        if (!isAdmin(user)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<html><body><h2>Forbidden</h2><p>Admin permission required.</p></body></html>");
            return;
        }

        HttpSession session = request.getSession(true);
        String csrf = generateCsrf();
        session.setAttribute(CSRF_KEY, csrf);

        boolean saved = "1".equals(request.getParameter("saved"));
        String error = request.getParameter("error");
        String test = request.getParameter("test");
        String testMsg = request.getParameter("testMsg");
        String testIssueKey = request.getParameter("testIssueKey");
        if (testIssueKey == null || testIssueKey.trim().isEmpty()) {
            testIssueKey = "DEMO-1";
        }

        // Non-secret values
        String clientId = safe(DocusignConfig.getString("DOCUSIGN_CLIENT_ID", ""));
        String accountId = safe(DocusignConfig.getString("DOCUSIGN_ACCOUNT_ID", ""));
        String enforceAccountId = safe(DocusignConfig.getString("DOCUSIGN_ENFORCE_ACCOUNT_ID", "false"));
        String oauthBase = safe(DocusignConfig.getString("DOCUSIGN_OAUTH_BASE", ""));
        String restBase = safe(DocusignConfig.getString("DOCUSIGN_REST_BASE", ""));
        String origin = safe(DocusignConfig.getString("DOCUSIGN_ORIGIN", ""));
        String redirectUri = safe(DocusignConfig.getString("DOCUSIGN_REDIRECT_URI", ""));
        String webhookUrl = safe(DocusignConfig.getString("DOCUSIGN_WEBHOOK_URL", ""));
        String webhookActor = safe(DocusignConfig.getString("DOCUSIGN_WEBHOOK_ACTOR", "admin"));
        String webhookRequireAuth = safe(DocusignConfig.getString("DOCUSIGN_WEBHOOK_REQUIRE_AUTH", "false"));
        String webhookIncludeSecret = safe(DocusignConfig.getString("DOCUSIGN_WEBHOOK_INCLUDE_SECRET", "true"));
        String webhookIncludeIssueKey = safe(DocusignConfig.getString("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY", "false"));

        // Secrets (presence only)
        boolean connectHmacKeySet = isPresent(DocusignConfig.getSecretString("DOCUSIGN_CONNECT_HMAC_KEY", null));
        boolean webhookSecretSet = isPresent(DocusignConfig.getSecretString("DOCUSIGN_WEBHOOK_SECRET", null));

        boolean cryptoOk = false;
        String cryptoErr = null;
        try {
            String enc = DocusignCrypto.encryptToString("ping");
            String dec = DocusignCrypto.decryptFromString(enc);
            cryptoOk = "ping".equals(dec);
        } catch (Exception e) {
            cryptoOk = false;
            cryptoErr = e.getMessage() != null ? e.getMessage() : "Unknown error";
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");

        StringBuilder html = new StringBuilder();
        html.append("<html><head>");
        html.append("<meta name='decorator' content='atl.admin'/>");
        html.append("<meta name='admin.active.section' content='admin_system_menu/advanced_menu_section/advanced_section'/>");
        html.append("<title>DocuSign Settings</title>");
        html.append("</head><body>");
        html.append("<h1>DocuSign Settings</h1>");

        if (saved) {
            html.append("<div class='aui-message aui-message-success'><p>Saved.</p></div>");
        }
        if (error != null && !error.trim().isEmpty()) {
            html.append("<div class='aui-message aui-message-error'><p>")
                    .append(escapeHtml(error))
                    .append("</p></div>");
        }
        if (test != null && !test.trim().isEmpty()) {
            boolean ok = "ok".equalsIgnoreCase(test);
            html.append("<div class='aui-message ").append(ok ? "aui-message-success" : "aui-message-warning").append("'><p>");
            html.append(ok ? "Webhook test passed." : "Webhook test failed.");
            if (testMsg != null && !testMsg.trim().isEmpty()) {
                html.append(" ").append(escapeHtml(testMsg));
            }
            html.append("</p></div>");
        }

        html.append("<div class='aui-message aui-message-info'><p>");
        html.append("Encryption: ").append(cryptoOk ? "OK" : "NOT OK");
        if (!cryptoOk && cryptoErr != null) {
            html.append(" (").append(escapeHtml(cryptoErr)).append(")");
        }
        html.append("</p></div>");

        html.append("<form class='aui' method='post' action=''>");
        html.append("<input type='hidden' name='csrfToken' value='").append(escapeHtml(csrf)).append("'/>");

        html.append(fieldText("DOCUSIGN_CLIENT_ID", "DocuSign Client ID (Integration Key)", clientId, true));
        html.append(fieldText("DOCUSIGN_ACCOUNT_ID", "Preferred DocuSign Account ID (optional)", accountId, false));
        html.append(fieldBool("DOCUSIGN_ENFORCE_ACCOUNT_ID", "Enforce preferred account membership (restricts users to DOCUSIGN_ACCOUNT_ID)", enforceAccountId));
        html.append(fieldText("DOCUSIGN_OAUTH_BASE", "OAuth Base (demo: https://account-d.docusign.com)", oauthBase, false));
        html.append(fieldText("DOCUSIGN_REST_BASE", "REST Base (demo: https://demo.docusign.net/restapi)", restBase, false));
        html.append(fieldText("DOCUSIGN_ORIGIN", "Origin (for OAuth token exchange)", origin, false));
        html.append(fieldText("DOCUSIGN_REDIRECT_URI", "Redirect URI (callback)", redirectUri, false));

        html.append("<h2>Webhook (Instant Status Updates)</h2>");
        html.append(fieldText("DOCUSIGN_WEBHOOK_URL", "Webhook URL (public): https://<domain>/jira/rest/docusign/1.0/send/webhook", webhookUrl, false));
        html.append(fieldText("DOCUSIGN_WEBHOOK_ACTOR", "Webhook actor Jira user (defaults to admin)", webhookActor, false));
        html.append(fieldBool("DOCUSIGN_WEBHOOK_REQUIRE_AUTH", "Require webhook auth (recommended)", webhookRequireAuth));
        html.append(fieldBool("DOCUSIGN_WEBHOOK_INCLUDE_SECRET", "Include shared secret in webhook URL (dev fallback; can be logged)", webhookIncludeSecret));
        html.append(fieldBool("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY", "Include issueKey in webhook URL (debug only; prefer custom field mapping)", webhookIncludeIssueKey));

        html.append("<h3>Webhook Auth</h3>");
        html.append(fieldPassword("DOCUSIGN_CONNECT_HMAC_KEY", "DocuSign Connect HMAC Key (recommended)", connectHmacKeySet));
        html.append(fieldPassword("DOCUSIGN_WEBHOOK_SECRET", "Shared Webhook Secret (fallback)", webhookSecretSet));

        html.append("<h3>Webhook Test</h3>");
        html.append(fieldText("testIssueKey", "Issue key used for webhook self-test (must exist to validate persistence)", safe(testIssueKey), false));

        html.append("<div class='field-group'><label>&nbsp;</label><div class='field-group'>");
        html.append("<label><input type='checkbox' name='clearConnectHmacKey' value='1'/> Clear HMAC key</label><br/>");
        html.append("<label><input type='checkbox' name='clearWebhookSecret' value='1'/> Clear shared secret</label>");
        html.append("</div></div>");

        html.append("<div class='buttons-container'>");
        html.append("<div class='buttons'>");
        html.append("<button class='aui-button aui-button-primary' type='submit' name='action' value='save'>Save</button>");
        html.append("<button class='aui-button' type='submit' name='action' value='testWebhook'>Test Webhook</button>");
        html.append("<button class='aui-button' type='submit' name='action' value='genHmac'>Generate HMAC Key</button>");
        html.append("<button class='aui-button' type='submit' name='action' value='genSecret'>Generate Shared Secret</button>");
        html.append("</div></div>");

        html.append("</form>");
        html.append("</body></html>");

        response.getWriter().write(html.toString());
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        if (!isAdmin(user)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<html><body><h2>Forbidden</h2><p>Admin permission required.</p></body></html>");
            return;
        }

        HttpSession session = request.getSession(false);
        String expected = session != null ? (String) session.getAttribute(CSRF_KEY) : null;
        String actual = request.getParameter("csrfToken");
        if (session != null) {
            session.removeAttribute(CSRF_KEY);
        }
        if (expected == null || actual == null || !expected.equals(actual)) {
            response.sendRedirect(request.getRequestURI() + "?error=" + urlEncode("Invalid CSRF token"));
            return;
        }

        try {
            String action = request.getParameter("action");
            if (action == null || action.trim().isEmpty()) action = "save";

            if ("genHmac".equals(action)) {
                String generated = randomB64(32);
                DocusignConfig.setGlobalSecretString("DOCUSIGN_CONNECT_HMAC_KEY", generated);
                response.sendRedirect(request.getRequestURI() + "?saved=1");
                return;
            }
            if ("genSecret".equals(action)) {
                String generated = randomB64(32);
                DocusignConfig.setGlobalSecretString("DOCUSIGN_WEBHOOK_SECRET", generated);
                response.sendRedirect(request.getRequestURI() + "?saved=1");
                return;
            }

            // Non-secret settings (blank => remove)
            setGlobalString("DOCUSIGN_CLIENT_ID", request.getParameter("DOCUSIGN_CLIENT_ID"));
            setGlobalString("DOCUSIGN_ACCOUNT_ID", request.getParameter("DOCUSIGN_ACCOUNT_ID"));
            DocusignConfig.setGlobalString("DOCUSIGN_ENFORCE_ACCOUNT_ID", request.getParameter("DOCUSIGN_ENFORCE_ACCOUNT_ID") != null ? "true" : "false");
            setGlobalString("DOCUSIGN_OAUTH_BASE", request.getParameter("DOCUSIGN_OAUTH_BASE"));
            setGlobalString("DOCUSIGN_REST_BASE", request.getParameter("DOCUSIGN_REST_BASE"));
            setGlobalString("DOCUSIGN_ORIGIN", request.getParameter("DOCUSIGN_ORIGIN"));
            setGlobalString("DOCUSIGN_REDIRECT_URI", request.getParameter("DOCUSIGN_REDIRECT_URI"));
            setGlobalString("DOCUSIGN_WEBHOOK_URL", request.getParameter("DOCUSIGN_WEBHOOK_URL"));
            setGlobalString("DOCUSIGN_WEBHOOK_ACTOR", request.getParameter("DOCUSIGN_WEBHOOK_ACTOR"));
            // Checkbox semantics: missing param means unchecked => false.
            DocusignConfig.setGlobalString("DOCUSIGN_WEBHOOK_REQUIRE_AUTH", request.getParameter("DOCUSIGN_WEBHOOK_REQUIRE_AUTH") != null ? "true" : "false");
            DocusignConfig.setGlobalString("DOCUSIGN_WEBHOOK_INCLUDE_SECRET", request.getParameter("DOCUSIGN_WEBHOOK_INCLUDE_SECRET") != null ? "true" : "false");
            DocusignConfig.setGlobalString("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY", request.getParameter("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY") != null ? "true" : "false");

            // Secret settings (only update when set; clear via checkbox)
            if ("1".equals(request.getParameter("clearConnectHmacKey"))) {
                DocusignConfig.setGlobalSecretString("DOCUSIGN_CONNECT_HMAC_KEY", null);
            } else {
                setGlobalSecretIfProvided("DOCUSIGN_CONNECT_HMAC_KEY", request.getParameter("DOCUSIGN_CONNECT_HMAC_KEY"));
            }

            if ("1".equals(request.getParameter("clearWebhookSecret"))) {
                DocusignConfig.setGlobalSecretString("DOCUSIGN_WEBHOOK_SECRET", null);
            } else {
                setGlobalSecretIfProvided("DOCUSIGN_WEBHOOK_SECRET", request.getParameter("DOCUSIGN_WEBHOOK_SECRET"));
            }

            if ("testWebhook".equals(action)) {
                String issueKey = request.getParameter("testIssueKey");
                if (issueKey == null || issueKey.trim().isEmpty()) issueKey = "DEMO-1";
                TestResult tr = runWebhookSelfTest(issueKey.trim());
                response.sendRedirect(request.getRequestURI()
                        + "?test=" + urlEncode(tr.ok ? "ok" : "fail")
                        + "&testMsg=" + urlEncode(tr.message)
                        + "&testIssueKey=" + urlEncode(issueKey.trim()));
            } else {
                response.sendRedirect(request.getRequestURI() + "?saved=1");
            }
        } catch (Exception e) {
            response.sendRedirect(request.getRequestURI() + "?error=" + urlEncode(e.getMessage() != null ? e.getMessage() : "Save failed"));
        }
    }

    private boolean isAdmin(ApplicationUser user) {
        return user != null && permissionManager != null && permissionManager.hasPermission(Permissions.ADMINISTER, user);
    }

    private static void setGlobalString(String key, String raw) {
        if (raw == null) return;
        String v = raw.trim();
        DocusignConfig.setGlobalString(key, v.isEmpty() ? null : v);
    }

    private static void setGlobalSecretIfProvided(String key, String raw) {
        if (raw == null) return;
        String v = raw.trim();
        if (v.isEmpty()) return;
        DocusignConfig.setGlobalSecretString(key, v);
    }

    private static String fieldText(String name, String label, String value, boolean required) {
        StringBuilder b = new StringBuilder();
        b.append("<div class='field-group'>");
        b.append("<label for='").append(escapeHtml(name)).append("'>").append(escapeHtml(label));
        if (required) b.append(" <span class='aui-icon icon-required'>required</span>");
        b.append("</label>");
        b.append("<input class='text' type='text' id='").append(escapeHtml(name)).append("' name='").append(escapeHtml(name))
                .append("' value='").append(escapeHtml(value)).append("'/>");
        b.append("</div>");
        return b.toString();
    }

    private static String fieldPassword(String name, String label, boolean isSet) {
        StringBuilder b = new StringBuilder();
        b.append("<div class='field-group'>");
        b.append("<label for='").append(escapeHtml(name)).append("'>").append(escapeHtml(label)).append("</label>");
        b.append("<input class='text' type='password' autocomplete='new-password' id='").append(escapeHtml(name)).append("' name='")
                .append(escapeHtml(name)).append("' value='' placeholder='").append(isSet ? "Configured (enter to replace)" : "Not set").append("'/>");
        b.append("</div>");
        return b.toString();
    }

    private static String fieldBool(String name, String label, String value) {
        String v = value != null ? value.trim() : "";
        boolean checked = "true".equalsIgnoreCase(v);
        StringBuilder b = new StringBuilder();
        b.append("<div class='field-group'>");
        b.append("<label>&nbsp;</label>");
        b.append("<div class='checkbox'>");
        b.append("<label><input type='checkbox' name='").append(escapeHtml(name)).append("' value='true' ");
        if (checked) b.append("checked='checked' ");
        b.append("/> ").append(escapeHtml(label)).append("</label>");
        b.append("</div>");
        b.append("</div>");
        return b.toString();
    }

    private static boolean isPresent(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String safe(String v) {
        return v != null ? v : "";
    }

    private static String generateCsrf() {
        byte[] b = new byte[24];
        RAND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String randomB64(int bytes) {
        byte[] b = new byte[bytes];
        RAND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static final class TestResult {
        final boolean ok;
        final String message;
        TestResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message != null ? message : "";
        }
    }

    private TestResult runWebhookSelfTest(String issueKey) {
        String webhookUrl = DocusignConfig.getString("DOCUSIGN_WEBHOOK_URL", null);
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return new TestResult(false, "DOCUSIGN_WEBHOOK_URL is not set.");
        }

        try {
            IssueManager issueManager = ComponentAccessor.getIssueManager();
            Issue issue = issueManager != null ? issueManager.getIssueObject(issueKey) : null;
            if (issue == null) {
                return new TestResult(false, "Issue not found: " + issueKey + " (use an existing issue key for the test).");
            }
        } catch (Exception ignore) {
            // If issue validation fails for any reason, proceed with the test anyway.
        }

        String envelopeId = UUID.randomUUID().toString();

        String xml = ""
                + "<EnvelopeStatus>"
                + "<EnvelopeID>" + envelopeId + "</EnvelopeID>"
                + "<Status>Delivered</Status>"
                + "<Subject>Test " + issueKey + "</Subject>"
                + "<CustomFields>"
                + "  <CustomField><Name>jiraIssueKey</Name><Value>" + issueKey + "</Value></CustomField>"
                + "</CustomFields>"
                + "<RecipientStatuses>"
                + "  <RecipientStatus><Email>test@example.com</Email><UserName>Test User</UserName><Status>Delivered</Status><RoutingOrder>1</RoutingOrder></RecipientStatus>"
                + "</RecipientStatuses>"
                + "</EnvelopeStatus>";

        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);

        String url = webhookUrl.trim();
        boolean includeIssueKey = "true".equalsIgnoreCase(DocusignConfig.getString("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY", "false"));
        if (includeIssueKey && !url.contains("issueKey=")) {
            url = url + (url.contains("?") ? "&" : "?") + "issueKey=" + urlEncode(issueKey);
        }

        String connectHmacKey = DocusignConfig.getSecretString("DOCUSIGN_CONNECT_HMAC_KEY", null);
        String secret = DocusignConfig.getSecretString("DOCUSIGN_WEBHOOK_SECRET", null);
        boolean includeSecret = "true".equalsIgnoreCase(DocusignConfig.getString("DOCUSIGN_WEBHOOK_INCLUDE_SECRET", "true"));
        if ((connectHmacKey == null || connectHmacKey.trim().isEmpty()) && includeSecret && secret != null && !secret.trim().isEmpty()) {
            if (!url.contains("secret=")) {
                url = url + "&secret=" + urlEncode(secret.trim());
            }
        }

        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(xml, ContentType.TEXT_XML));
            post.setHeader("Content-Type", "text/xml");
            if (connectHmacKey != null && !connectHmacKey.trim().isEmpty()) {
                String sig = computeHmacBase64(payload, connectHmacKey.trim(), "HmacSHA256");
                post.setHeader("X-DocuSign-Signature-1", sig);
            }
            try (CloseableHttpResponse resp = client.execute(post)) {
                int code = resp.getStatusLine().getStatusCode();
                String body = resp.getEntity() != null ? EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8) : "";
                if (code >= 200 && code < 300) {
                    if (body.contains("\"ignored\":true") || body.contains("\"ignored\": true")) {
                        return new TestResult(false, "HTTP " + code + " but webhook ignored the event (issueKey may not exist or mapping failed).");
                    }
                    return new TestResult(true, "HTTP " + code);
                }
                return new TestResult(false, "HTTP " + code + ": " + body);
            }
        } catch (Exception e) {
            return new TestResult(false, e.getMessage() != null ? e.getMessage() : "Request failed");
        }
    }

    private static String computeHmacBase64(byte[] payload, String secret, String algorithm) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        byte[] digest = mac.doFinal(payload != null ? payload : new byte[0]);
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s != null ? s : "", "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
