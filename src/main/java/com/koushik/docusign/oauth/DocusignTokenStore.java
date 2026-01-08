package com.koushik.docusign.oauth;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koushik.docusign.docusign.DocusignOAuthConfig;
import com.koushik.docusign.security.DocusignCrypto;
import com.koushik.docusign.servlet.DocusignCallbackServlet;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores DocuSign OAuth tokens per Jira user.
 *
 * NOTE: This uses Jira global plugin settings (stored in DB) for persistence.
 * Tokens are encrypted at rest using {@link com.koushik.docusign.security.DocusignCrypto}.
 */
public final class DocusignTokenStore {

    private static final String PLUGIN_KEY = "com.koushik.docusign.jira-docusign-plugin";
    private static final String PREFIX = PLUGIN_KEY + ".oauth.";
    private static final Gson GSON = new Gson();

    private static final Map<String, Object> USER_LOCKS = new ConcurrentHashMap<>();

    private DocusignTokenStore() {}

    public static ApplicationUser getCurrentUser() {
        JiraAuthenticationContext auth = ComponentAccessor.getJiraAuthenticationContext();
        return auth != null ? auth.getLoggedInUser() : null;
    }

    public static boolean isConnected(ApplicationUser user, HttpSession session) {
        if (user == null) return false;
        try {
            String token = getValidAccessToken(user, session, true);
            return token != null && !token.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static String getValidAccessToken(ApplicationUser user, HttpSession session) {
        return getValidAccessToken(user, session, true);
    }

    private static String getValidAccessToken(ApplicationUser user, HttpSession session, boolean allowRefresh) {
        if (user == null) return null;

        // 1) Session token (fast path)
        String token = getSessionTokenIfValid(session);
        if (token != null) return token;

        // 2) In-memory token store (dev convenience; may be empty after restart)
        token = getTokenFromInMemoryStore(user);
        if (token != null) return token;

        // 3) Persisted access token
        TokenRecord rec = readTokenRecord(user.getKey());
        String persistedAccessToken = decryptToken(rec != null ? rec.accessTokenEnc : null, rec != null ? rec.accessToken : null);
        if (rec != null && persistedAccessToken != null && rec.expiresAtMs != null) {
            if (System.currentTimeMillis() < rec.expiresAtMs) {
                setSessionToken(session, user.getKey(), persistedAccessToken, rec.expiresAtMs);
                return persistedAccessToken;
            }
        }

        // 4) Refresh token flow
        if (!allowRefresh) return null;
        String refreshToken = decryptToken(rec != null ? rec.refreshTokenEnc : null, rec != null ? rec.refreshToken : null);
        if (refreshToken == null || refreshToken.trim().isEmpty()) return null;

        Object lock = USER_LOCKS.computeIfAbsent(user.getKey(), k -> new Object());
        synchronized (lock) {
            // Re-check after acquiring lock (another thread may have refreshed)
            TokenRecord rec2 = readTokenRecord(user.getKey());
            String persistedAccessToken2 = decryptToken(rec2 != null ? rec2.accessTokenEnc : null, rec2 != null ? rec2.accessToken : null);
            if (rec2 != null && persistedAccessToken2 != null && rec2.expiresAtMs != null) {
                if (System.currentTimeMillis() < rec2.expiresAtMs) {
                    setSessionToken(session, user.getKey(), persistedAccessToken2, rec2.expiresAtMs);
                    return persistedAccessToken2;
                }
            }
            TokenResponse refreshed = refreshAccessToken(refreshToken);
            long expiresAtMs = System.currentTimeMillis() + (refreshed.expiresInSec * 1000L);
            TokenRecord next = new TokenRecord();
            next.accessTokenEnc = encryptToken(refreshed.accessToken);
            next.refreshTokenEnc = encryptToken((refreshed.refreshToken != null && !refreshed.refreshToken.trim().isEmpty())
                    ? refreshed.refreshToken
                    : refreshToken);
            next.expiresAtMs = expiresAtMs;
            // Preserve non-secret account context across refreshes.
            next.accountId = rec2 != null ? rec2.accountId : null;
            next.restBase = rec2 != null ? rec2.restBase : null;
            writeTokenRecord(user.getKey(), next);
            String nextAccessToken = decryptToken(next.accessTokenEnc, null);
            setSessionToken(session, user.getKey(), nextAccessToken, next.expiresAtMs);
            return nextAccessToken;
        }
    }

    public static void saveTokensFromCallback(ApplicationUser user, HttpSession session, String accessToken, String refreshToken, long expiresInSec) {
        saveTokensFromCallback(user, session, accessToken, refreshToken, expiresInSec, null, null);
    }

    public static void saveTokensFromCallback(ApplicationUser user,
                                              HttpSession session,
                                              String accessToken,
                                              String refreshToken,
                                              long expiresInSec,
                                              String accountId,
                                              String restBase) {
        if (user == null || user.getKey() == null) return;
        long expiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L);
        TokenRecord rec = new TokenRecord();
        rec.accessTokenEnc = encryptToken(accessToken);
        rec.refreshTokenEnc = encryptToken(refreshToken);
        rec.expiresAtMs = expiresAtMs;
        rec.accountId = (accountId != null && !accountId.trim().isEmpty()) ? accountId.trim() : null;
        rec.restBase = (restBase != null && !restBase.trim().isEmpty()) ? restBase.trim() : null;
        writeTokenRecord(user.getKey(), rec);
        setSessionToken(session, user.getKey(), accessToken, expiresAtMs);
    }

    public static void disconnect(ApplicationUser user, HttpSession session) {
        if (user == null || user.getKey() == null) return;
        PluginSettings settings = getGlobalSettings();
        if (settings != null) {
            settings.remove(PREFIX + user.getKey());
        }
        if (session != null) {
            session.removeAttribute(DocusignCallbackServlet.SESSION_ACCESS_TOKEN);
            session.removeAttribute(DocusignCallbackServlet.SESSION_EXPIRES_AT);
            session.removeAttribute(DocusignCallbackServlet.SESSION_EXPIRES_IN);
            session.removeAttribute(DocusignCallbackServlet.SESSION_TOKEN_KEY);
        }
    }

    public static Long getExpiresAtMs(ApplicationUser user, HttpSession session) {
        Long exp = getSessionExpiresAt(session);
        if (exp != null) return exp;
        TokenRecord rec = readTokenRecord(user != null ? user.getKey() : null);
        return rec != null ? rec.expiresAtMs : null;
    }

    public static String getRestBaseOverride(ApplicationUser user) {
        TokenRecord rec = readTokenRecord(user != null ? user.getKey() : null);
        String v = rec != null ? rec.restBase : null;
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    public static String getAccountIdOverride(ApplicationUser user) {
        TokenRecord rec = readTokenRecord(user != null ? user.getKey() : null);
        String v = rec != null ? rec.accountId : null;
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static String getTokenFromInMemoryStore(ApplicationUser user) {
        try {
            String name = user.getName();
            if (name != null && !name.trim().isEmpty()) {
                String byName = DocusignCallbackServlet.getAccessToken(name.trim());
                if (byName != null && !byName.trim().isEmpty()) {
                    return byName;
                }
            }
        } catch (Exception ignore) {}
        try {
            String key = user.getKey();
            if (key != null && !key.trim().isEmpty()) {
                String byKey = DocusignCallbackServlet.getAccessToken(key.trim());
                if (byKey != null && !byKey.trim().isEmpty()) {
                    return byKey;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static String getSessionTokenIfValid(HttpSession session) {
        if (session == null) return null;
        try {
            Object tok = session.getAttribute(DocusignCallbackServlet.SESSION_ACCESS_TOKEN);
            Object exp = session.getAttribute(DocusignCallbackServlet.SESSION_EXPIRES_AT);
            String token = (tok instanceof String) ? ((String) tok).trim() : null;
            Long expiresAt = null;
            if (exp instanceof Long) expiresAt = (Long) exp;
            if (exp instanceof String) {
                try { expiresAt = Long.parseLong((String) exp); } catch (Exception ignore) { expiresAt = null; }
            }
            if (token != null && !token.isEmpty()) {
                if (expiresAt == null || System.currentTimeMillis() < expiresAt) {
                    return token;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static Long getSessionExpiresAt(HttpSession session) {
        if (session == null) return null;
        Object exp = session.getAttribute(DocusignCallbackServlet.SESSION_EXPIRES_AT);
        if (exp instanceof Long) return (Long) exp;
        if (exp instanceof String) {
            try { return Long.parseLong((String) exp); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    private static void setSessionToken(HttpSession session, String tokenKey, String accessToken, long expiresAtMs) {
        if (session == null) return;
        session.setAttribute(DocusignCallbackServlet.SESSION_ACCESS_TOKEN, accessToken);
        session.setAttribute(DocusignCallbackServlet.SESSION_EXPIRES_AT, expiresAtMs);
        session.setAttribute(DocusignCallbackServlet.SESSION_TOKEN_KEY, tokenKey);
    }

    private static TokenResponse refreshAccessToken(String refreshToken) {
        String tokenUrl = DocusignOAuthConfig.getTokenUrl();
        StringBuilder body = new StringBuilder();
        body.append("grant_type=refresh_token");
        body.append("&refresh_token=").append(urlEncode(refreshToken));
        body.append("&client_id=").append(urlEncode(DocusignOAuthConfig.getClientId()));

        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpPost post = new HttpPost(tokenUrl);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            post.setHeader("Origin", DocusignOAuthConfig.getOrigin());
            post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_FORM_URLENCODED));

            try (CloseableHttpResponse resp = client.execute(post)) {
                int statusCode = resp.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (statusCode < 200 || statusCode >= 300) {
                    throw new RuntimeException("HTTP " + statusCode + " from DocuSign token endpoint: " + responseBody);
                }
                JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
                if (json == null || !json.has("access_token")) {
                    throw new RuntimeException("Token response missing access_token: " + responseBody);
                }
                TokenResponse tr = new TokenResponse();
                tr.accessToken = json.get("access_token").getAsString();
                tr.expiresInSec = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;
                tr.refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
                return tr;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to refresh DocuSign token: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value != null ? value : "", "UTF-8");
        } catch (Exception e) {
            return value != null ? value : "";
        }
    }

    private static TokenRecord readTokenRecord(String userKey) {
        if (userKey == null || userKey.trim().isEmpty()) return null;
        PluginSettings settings = getGlobalSettings();
        if (settings == null) return null;
        Object raw = settings.get(PREFIX + userKey);
        if (raw == null) return null;
        try {
            return GSON.fromJson(String.valueOf(raw), TokenRecord.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void writeTokenRecord(String userKey, TokenRecord rec) {
        if (userKey == null || userKey.trim().isEmpty()) return;
        PluginSettings settings = getGlobalSettings();
        if (settings == null) return;
        settings.put(PREFIX + userKey, GSON.toJson(rec));
    }

    private static PluginSettings getGlobalSettings() {
        try {
            PluginSettingsFactory factory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
            if (factory == null) return null;
            return factory.createGlobalSettings();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static final class TokenRecord {
        // Legacy plaintext (kept for backward compatibility when upgrading)
        String accessToken;
        String refreshToken;

        // New encrypted fields
        String accessTokenEnc;
        String refreshTokenEnc;
        Long expiresAtMs;

        // Non-secret account context (improves correctness; helps avoid hardcoding base URIs)
        String accountId;
        String restBase;
    }

    private static final class TokenResponse {
        String accessToken;
        String refreshToken;
        long expiresInSec;
    }

    private static String encryptToken(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.isEmpty()) return "";
        try {
            return DocusignCrypto.encryptToString(t);
        } catch (Exception e) {
            // Fail open for dev; production should configure key and re-connect.
            return t;
        }
    }

    private static String decryptToken(String encrypted, String legacyPlaintext) {
        String v = (encrypted != null && !encrypted.trim().isEmpty()) ? encrypted.trim() : null;
        if (v == null || v.isEmpty()) {
            v = (legacyPlaintext != null && !legacyPlaintext.trim().isEmpty()) ? legacyPlaintext.trim() : null;
        }
        if (v == null) return null;
        try {
            return DocusignCrypto.decryptFromString(v);
        } catch (Exception e) {
            return null;
        }
    }
}
