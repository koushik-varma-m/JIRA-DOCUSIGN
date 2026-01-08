package com.koushik.docusign.servlet;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.koushik.docusign.config.DocusignConfig;
import com.koushik.docusign.docusign.DocusignOAuthConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import com.koushik.docusign.oauth.DocusignTokenStore;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet that handles DocuSign OAuth callback.
 * 
 * This servlet:
 * - Reads authorization code from request
 * - Retrieves code_verifier from HTTP session
 * - Exchanges authorization code for access token
 * - Stores token in memory (Map keyed by user)
 * - Returns success message
 * 
 * Path: /plugins/servlet/docusign/callback
 */
public class DocusignCallbackServlet extends HttpServlet {

    /**
     * Session attribute key for code_verifier
     */
    private static final String SESSION_CODE_VERIFIER = "docusign.code_verifier";
    private static final String SESSION_OAUTH_STATE = "docusign.oauth_state";

    /**
     * Session attribute keys for token persistence.
     *
     * Stored as primitive types (String/Long) so it survives plugin reloads without classloader issues.
     */
    public static final String SESSION_ACCESS_TOKEN = "docusign.access_token";
    public static final String SESSION_EXPIRES_AT = "docusign.expires_at";
    public static final String SESSION_EXPIRES_IN = "docusign.expires_in";
    public static final String SESSION_TOKEN_KEY = "docusign.token_key";

    /**
     * In-memory token storage (keyed by username)
     * TODO: Replace with persistent storage (database) in production
     */
    private static final Map<String, TokenInfo> tokenStore = new ConcurrentHashMap<>();

    /**
     * JSON parser
     */
    private static final Gson GSON = new Gson();

    /**
     * Token information stored for each user
     */
    public static class TokenInfo {
        private final String accessToken;
        private final long expiresAt;
        private final long expiresIn;
        private final String refreshToken;

        public TokenInfo(String accessToken, String refreshToken, long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
            // expiresAt = current time + expires_in seconds
            this.expiresAt = System.currentTimeMillis() + (expiresIn * 1000);
        }

        public String getAccessToken() {
            return accessToken;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public long getExpiresIn() {
            return expiresIn;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        String error = request.getParameter("error");
        String errorDescription = request.getParameter("error_description");

        if (session == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                    "<h2>DocuSign OAuth Error</h2>" +
                    "<p>Missing Jira session. Please start the OAuth flow again from Jira.</p>" +
                    "<p><a href='/jira'>Return to Jira</a></p>" +
                    "</body></html>"
            );
            return;
        }

        // Check for OAuth errors
        if (error != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                "<h2>DocuSign OAuth Error</h2>" +
                "<p><strong>Error:</strong> " + escapeHtml(error) + "</p>" +
                (errorDescription != null ? "<p><strong>Description:</strong> " + escapeHtml(errorDescription) + "</p>" : "") +
                "<p><a href='/jira'>Return to Jira</a></p>" +
                "</body></html>"
            );
            return;
        }

        // Get authorization code
        String authCode = request.getParameter("code");
        if (authCode == null || authCode.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                "<h2>DocuSign OAuth Error</h2>" +
                "<p>Authorization code not found in callback.</p>" +
                "<p><a href='/jira'>Return to Jira</a></p>" +
                "</body></html>"
            );
            return;
        }

        // Get code_verifier from session
        if (session == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                "<h2>DocuSign OAuth Error</h2>" +
                "<p>Session expired. Please try again.</p>" +
                "<p><a href='/jira'>Return to Jira</a></p>" +
                "</body></html>"
            );
            return;
        }

        String codeVerifier = (String) session.getAttribute(SESSION_CODE_VERIFIER);
        if (codeVerifier == null || codeVerifier.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                "<h2>DocuSign OAuth Error</h2>" +
                "<p>Code verifier not found in session. Please try again.</p>" +
                "<p><a href='/jira'>Return to Jira</a></p>" +
                "</body></html>"
            );
            return;
        }

        try {
            // CSRF protection: validate state
            String expectedState = (String) session.getAttribute(SESSION_OAUTH_STATE);
            String actualState = request.getParameter("state");
            session.removeAttribute(SESSION_OAUTH_STATE);
            if (expectedState != null && (actualState == null || !expectedState.equals(actualState))) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write(
                    "<html><body>" +
                        "<h2>DocuSign OAuth Error</h2>" +
                        "<p>Invalid OAuth state. Please try again.</p>" +
                        "<p><a href='/jira'>Return to Jira</a></p>" +
                        "</body></html>"
                );
                return;
            }

            // Exchange authorization code for access token
            TokenInfo tokenInfo = exchangeCodeForToken(authCode, codeVerifier);

            // Determine DocuSign account/baseUri for this user via /oauth/userinfo.
            // If DOCUSIGN_ACCOUNT_ID is set, treat it as a preferred account unless enforcement is enabled.
            String preferredAccountId = null;
            try {
                preferredAccountId = DocusignConfig.getString("DOCUSIGN_ACCOUNT_ID", null);
            } catch (Exception ignore) {
                preferredAccountId = null;
            }
            boolean enforceAccount = false;
            try {
                String v = DocusignConfig.getString("DOCUSIGN_ENFORCE_ACCOUNT_ID", "false");
                enforceAccount = "true".equalsIgnoreCase(v != null ? v.trim() : "");
            } catch (Exception ignore) {
                enforceAccount = false;
            }
            UserInfoContext userCtx = null;
            userCtx = resolveUserAccountContext(tokenInfo.getAccessToken(),
                    (preferredAccountId != null ? preferredAccountId.trim() : null),
                    enforceAccount);

            // Prefer Jira username to make token usable across sessions/hostnames; fallback to session id.
            String tokenKey = getUsername(request, session);

            // Store token in memory
            tokenStore.put(tokenKey, tokenInfo);

            // Also store token in the HTTP session so it survives plugin reloads during dev.
            session.setAttribute(SESSION_ACCESS_TOKEN, tokenInfo.getAccessToken());
            session.setAttribute(SESSION_EXPIRES_AT, tokenInfo.getExpiresAt());
            session.setAttribute(SESSION_EXPIRES_IN, tokenInfo.getExpiresIn());
            session.setAttribute(SESSION_TOKEN_KEY, tokenKey);

            // Persist tokens per user (for production readiness)
            ApplicationUser user = resolveUser(request);
            if (user != null) {
                DocusignTokenStore.saveTokensFromCallback(
                        user,
                        session,
                        tokenInfo.getAccessToken(),
                        tokenInfo.getRefreshToken(),
                        tokenInfo.getExpiresIn(),
                        userCtx != null ? userCtx.accountId : null,
                        userCtx != null ? userCtx.restBase : null
                );
            }

            // Clear code_verifier from session (security best practice)
            session.removeAttribute(SESSION_CODE_VERIFIER);

            // Redirect back to the calling page (or Jira home) instead of showing a standalone message
            String target = (String) session.getAttribute("docusign.return_to");
            session.removeAttribute("docusign.return_to");
            if (target == null || target.trim().isEmpty()) {
                String context = request.getContextPath() != null ? request.getContextPath() : "";
                target = context.isEmpty() ? "/" : context + "/";
            }
            response.sendRedirect(target);

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                "<h2>DocuSign OAuth Error</h2>" +
                "<p>Failed to exchange authorization code for token: " + escapeHtml(e.getMessage()) + "</p>" +
                "<p><a href='/jira'>Return to Jira</a></p>" +
                "</body></html>"
            );
        }
    }

    /**
     * Exchanges authorization code for access token.
     * 
     * @param authCode The authorization code from DocuSign
     * @param codeVerifier The PKCE code_verifier from session
     * @return TokenInfo with access token and expiration
     * @throws Exception if token exchange fails
     */
    private TokenInfo exchangeCodeForToken(String authCode, String codeVerifier) throws Exception {
        String tokenUrl = DocusignOAuthConfig.getTokenUrl();

        // Build token request body (form-encoded)
        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code");
        body.append("&code=").append(encodeUrl(authCode));
        body.append("&client_id=").append(encodeUrl(DocusignOAuthConfig.getClientId()));
        body.append("&redirect_uri=").append(encodeUrl(DocusignOAuthConfig.getRedirectUri()));
        body.append("&code_verifier=").append(encodeUrl(codeVerifier));

        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpPost post = new HttpPost(tokenUrl);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            // DocuSign may require Origin header for public clients when CORS origins are set
            post.setHeader("Origin", DocusignOAuthConfig.getOrigin());
            post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_FORM_URLENCODED));

            try (CloseableHttpResponse resp = client.execute(post)) {
                int statusCode = resp.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (statusCode < 200 || statusCode >= 300) {
                    throw new RuntimeException(
                        "HTTP " + statusCode + " from DocuSign token endpoint: " + responseBody
                    );
                }

                // Parse JSON response
                JsonObject json = GSON.fromJson(responseBody, JsonObject.class);

                if (!json.has("access_token")) {
                    throw new RuntimeException("Token response missing access_token: " + responseBody);
                }

                String accessToken = json.get("access_token").getAsString();
                long expiresIn = json.has("expires_in") 
                    ? json.get("expires_in").getAsLong() 
                    : 3600; // Default to 1 hour if not provided
                String refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;

                return new TokenInfo(accessToken, refreshToken, expiresIn);
            }
        }
    }

    private static final class UserInfoContext {
        String accountId;
        String restBase;
    }

    private UserInfoContext resolveUserAccountContext(String accessToken, String preferredAccountId, boolean enforcePreferred) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalStateException("Missing access token");
        }

        String userInfoUrl = DocusignOAuthConfig.getOAuthBaseUrl() + "/oauth/userinfo";
        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpGet get = new HttpGet(userInfoUrl);
            get.setHeader("Authorization", "Bearer " + accessToken);

            try (CloseableHttpResponse resp = client.execute(get)) {
                int statusCode = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (statusCode < 200 || statusCode >= 300) {
                    throw new RuntimeException("HTTP " + statusCode + " from DocuSign userinfo endpoint: " + body);
                }
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                if (json == null || !json.has("accounts") || !json.get("accounts").isJsonArray()) {
                    throw new RuntimeException("DocuSign userinfo response missing accounts");
                }

                JsonObject preferred = null;
                JsonObject def = null;
                JsonObject first = null;
                for (com.google.gson.JsonElement el : json.getAsJsonArray("accounts")) {
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject acct = el.getAsJsonObject();
                    if (first == null) first = acct;
                    String accountId = null;
                    if (acct.has("account_id") && acct.get("account_id").isJsonPrimitive()) {
                        accountId = acct.get("account_id").getAsString();
                    } else if (acct.has("accountId") && acct.get("accountId").isJsonPrimitive()) {
                        accountId = acct.get("accountId").getAsString();
                    }
                    boolean isDefault = false;
                    if (acct.has("is_default") && acct.get("is_default").isJsonPrimitive()) {
                        try {
                            isDefault = acct.get("is_default").getAsBoolean();
                        } catch (Exception ignore) {
                            isDefault = false;
                        }
                    } else if (acct.has("isDefault") && acct.get("isDefault").isJsonPrimitive()) {
                        try {
                            isDefault = acct.get("isDefault").getAsBoolean();
                        } catch (Exception ignore) {
                            isDefault = false;
                        }
                    }
                    if (isDefault && def == null) {
                        def = acct;
                    }
                    if (preferredAccountId != null && !preferredAccountId.trim().isEmpty() && accountId != null
                            && accountId.trim().equals(preferredAccountId.trim())) {
                        preferred = acct;
                    }
                }

                if (enforcePreferred && preferredAccountId != null && !preferredAccountId.trim().isEmpty() && preferred == null) {
                    throw new IllegalStateException(
                        "Your DocuSign user is not a member of the configured DocuSign account (" + preferredAccountId + "). " +
                            "Ask your DocuSign admin to add you to that account, or ask your Jira admin to change DOCUSIGN_ACCOUNT_ID."
                    );
                }

                JsonObject chosen = preferred != null ? preferred : (def != null ? def : first);
                if (chosen == null) {
                    throw new RuntimeException("DocuSign userinfo response had no usable accounts");
                }

                String chosenAccountId = null;
                if (chosen.has("account_id") && chosen.get("account_id").isJsonPrimitive()) {
                    chosenAccountId = chosen.get("account_id").getAsString();
                } else if (chosen.has("accountId") && chosen.get("accountId").isJsonPrimitive()) {
                    chosenAccountId = chosen.get("accountId").getAsString();
                }
                // base_uri can be returned as base_uri/baseUri; it may already contain /restapi.
                String restBase = null;
                String baseUri = null;
                if (chosen.has("base_uri") && chosen.get("base_uri").isJsonPrimitive()) {
                    baseUri = chosen.get("base_uri").getAsString();
                } else if (chosen.has("baseUri") && chosen.get("baseUri").isJsonPrimitive()) {
                    baseUri = chosen.get("baseUri").getAsString();
                }
                if (baseUri != null) {
                    String b = baseUri.trim();
                    if (!b.isEmpty()) {
                        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
                        restBase = b.endsWith("/restapi") ? b : (b + "/restapi");
                    }
                }

                UserInfoContext ctx = new UserInfoContext();
                ctx.accountId = chosenAccountId;
                ctx.restBase = restBase;
                return ctx;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve DocuSign account context: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"), e);
        }
    }

    private ApplicationUser resolveUser(HttpServletRequest request) {
        try {
            String remote = request != null ? request.getRemoteUser() : null;
            if (remote == null || remote.trim().isEmpty()) return null;
            UserManager userManager = ComponentAccessor.getUserManager();
            return userManager != null ? userManager.getUserByName(remote.trim()) : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Gets username from request or session.
     * 
     * @param request HTTP request
     * @param session HTTP session
     * @return Username or session ID as fallback
     */
    private String getUsername(HttpServletRequest request, HttpSession session) {
        try {
            String remote = request != null ? request.getRemoteUser() : null;
            if (remote != null && !remote.trim().isEmpty()) {
                return remote.trim();
            }
        } catch (Exception ignore) {
        }
        return session != null ? session.getId() : "anonymous";
    }

    /**
     * URL-encodes a string for use in form data.
     */
    private String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 encoding not available", e);
        }
    }

    /**
     * Escapes HTML special characters.
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * Masks token for display (shows first 8 and last 4 characters).
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "***";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }

    /**
     * Gets the access token for a user (for use by other components).
     * 
     * @param username Username
     * @return Access token or null if not found/expired
     */
    public static String getAccessToken(String username) {
        TokenInfo tokenInfo = tokenStore.get(username);
        if (tokenInfo == null || tokenInfo.isExpired()) {
            return null;
        }
        return tokenInfo.getAccessToken();
    }

    /**
     * Gets TokenInfo for a user (for use by other components).
     * 
     * @param username Username
     * @return TokenInfo or null if not found/expired
     */
    public static TokenInfo getTokenInfo(String username) {
        TokenInfo tokenInfo = tokenStore.get(username);
        if (tokenInfo == null || tokenInfo.isExpired()) {
            return null;
        }
        return tokenInfo;
    }
}
