package com.koushik.docusign.servlet;

import com.koushik.docusign.docusign.DocusignOAuthConfig;
import com.koushik.docusign.oauth.PkceUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Servlet that initiates DocuSign OAuth login with PKCE.
 * 
 * This servlet:
 * - Generates PKCE code_verifier and code_challenge
 * - Stores code_verifier in HTTP session
 * - Redirects user to DocuSign authorization endpoint
 * 
 * Path: /plugins/servlet/docusign/connect
 */
public class DocusignConnectServlet extends HttpServlet {

    /**
     * Session attribute key for storing code_verifier
     */
    private static final String SESSION_CODE_VERIFIER = "docusign.code_verifier";
    private static final String SESSION_RETURN_TO = "docusign.return_to";
    private static final String SESSION_OAUTH_STATE = "docusign.oauth_state";
    private static final SecureRandom RAND = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        HttpSession session = request.getSession(true);

        try {
            // Generate PKCE code_verifier and code_challenge
            String[] pkcePair = PkceUtil.generateCodePair();
            String codeVerifier = pkcePair[0];
            String codeChallenge = pkcePair[1];

            // Store code_verifier in session for later use in callback
            session.setAttribute(SESSION_CODE_VERIFIER, codeVerifier);
            // Store return target (for redirect after success)
            session.setAttribute(SESSION_RETURN_TO, resolveReturnUrl(request));
            // OAuth CSRF protection
            String state = generateState();
            session.setAttribute(SESSION_OAUTH_STATE, state);

            // Build authorization URL with PKCE parameters
            String authUrl = buildAuthorizationUrl(request, codeChallenge, state);

            // Redirect to DocuSign authorization endpoint
            response.sendRedirect(authUrl);

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                "<html><body>" +
                "<h2>DocuSign OAuth Error</h2>" +
                "<p>Failed to initiate OAuth login: " + escapeHtml(e.getMessage()) + "</p>" +
                "<p><a href='javascript:history.back()'>Go Back</a></p>" +
                "</body></html>"
            );
        }
    }

    /**
     * Builds the DocuSign authorization URL with all required OAuth parameters.
     * 
     * @param codeChallenge The PKCE code_challenge
     * @return Complete authorization URL
     */
    private String buildAuthorizationUrl(HttpServletRequest request, String codeChallenge, String state) {
        String callback = resolveRedirectUri(request);
        StringBuilder url = new StringBuilder(DocusignOAuthConfig.getAuthorizationUrl());
        
        // Required OAuth 2.0 parameters
        url.append("?response_type=code");
        url.append("&client_id=").append(encodeUrl(DocusignOAuthConfig.getClientId()));
        url.append("&redirect_uri=").append(encodeUrl(callback));
        url.append("&scope=").append(encodeUrl(DocusignOAuthConfig.getScopes()));
        
        // PKCE parameters
        url.append("&code_challenge=").append(encodeUrl(codeChallenge));
        url.append("&code_challenge_method=S256");
        if (state != null && !state.trim().isEmpty()) {
            url.append("&state=").append(encodeUrl(state));
        }
        
        return url.toString();
    }

    private String resolveRedirectUri(HttpServletRequest request) {
        // If user supplied explicit redirect URI (plugin setting/system/env), honor it
        try {
            String configured = com.koushik.docusign.config.DocusignConfig.getString("DOCUSIGN_REDIRECT_URI", null);
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
        } catch (Exception ignore) {}

        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String ctx = request.getContextPath() != null ? request.getContextPath() : "";
        StringBuilder base = new StringBuilder();
        base.append(scheme).append("://").append(host);
        if (!("http".equalsIgnoreCase(scheme) && port == 80) && !("https".equalsIgnoreCase(scheme) && port == 443)) {
            base.append(":").append(port);
        }
        base.append(ctx).append("/plugins/servlet/docusign/callback");
        return base.toString();
    }

    private String resolveReturnUrl(HttpServletRequest request) {
        String retParam = request.getParameter("returnUrl");
        String referer = request.getHeader("Referer");
        String fallback = request.getContextPath() != null && !request.getContextPath().isEmpty()
                ? request.getContextPath() + "/"
                : "/";
        if (retParam != null && !retParam.trim().isEmpty()) {
            return retParam;
        }
        if (referer != null && !referer.trim().isEmpty()) {
            return referer;
        }
        return fallback;
    }

    private String generateState() {
        byte[] b = new byte[24];
        RAND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * URL-encodes a string for use in query parameters.
     * 
     * @param value The string to encode
     * @return URL-encoded string
     */
    private String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is required by Java spec, so this should never happen
            throw new RuntimeException("UTF-8 encoding not available", e);
        }
    }

    /**
     * Escapes HTML special characters to prevent XSS.
     * 
     * @param text Text to escape
     * @return HTML-escaped text
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
}
