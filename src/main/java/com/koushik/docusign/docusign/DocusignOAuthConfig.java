package com.koushik.docusign.docusign;

import com.koushik.docusign.config.DocusignConfig;

/**
 * Configuration class for DocuSign OAuth constants.
 * 
 * Stores OAuth-related URLs, scopes, and configuration values.
 * Reads sensitive values (like CLIENT_ID) from environment variables.
 */
public class DocusignOAuthConfig {

    /**
     * DocuSign OAuth base URL for demo environment
     */
    public static final String OAUTH_BASE_URL = "https://account-d.docusign.com";

    /**
     * DocuSign OAuth authorization endpoint
     */
    public static final String AUTHORIZATION_URL = OAUTH_BASE_URL + "/oauth/auth";

    /**
     * DocuSign OAuth token endpoint
     */
    public static final String TOKEN_URL = OAUTH_BASE_URL + "/oauth/token";

    /**
     * OAuth redirect URI for the Jira plugin callback
     */
    public static final String REDIRECT_URI = "http://localhost:2990/jira/plugins/servlet/docusign/callback";

    /**
     * OAuth scopes required for DocuSign integration
     */
    // offline_access is required to receive a refresh_token for long-lived connections.
    public static final String SCOPES = "signature offline_access";

    /**
     * Gets the DocuSign Client ID (Integration Key) from environment variable.
     * 
     * @return Client ID from DOCUSIGN_CLIENT_ID environment variable
     * @throws IllegalStateException if DOCUSIGN_CLIENT_ID is not set
     */
    public static String getClientId() {
        return DocusignConfig.getRequiredString("DOCUSIGN_CLIENT_ID");
    }

    /**
     * Gets the OAuth base URL (can be overridden for production)
     * 
     * @return OAuth base URL (defaults to demo environment)
     */
    public static String getOAuthBaseUrl() {
        return DocusignConfig.getString("DOCUSIGN_OAUTH_BASE", OAUTH_BASE_URL);
    }

    /**
     * Gets the authorization URL (uses configured OAuth base URL)
     * 
     * @return Authorization URL
     */
    public static String getAuthorizationUrl() {
        return getOAuthBaseUrl() + "/oauth/auth";
    }

    /**
     * Gets the token URL (uses configured OAuth base URL)
     * 
     * @return Token URL
     */
    public static String getTokenUrl() {
        return getOAuthBaseUrl() + "/oauth/token";
    }

    /**
     * Gets the redirect URI (can be overridden via environment variable)
     * 
     * @return Redirect URI
     */
    public static String getRedirectUri() {
        return DocusignConfig.getString("DOCUSIGN_REDIRECT_URI", REDIRECT_URI);
    }

    /**
     * Gets the Origin header value for public client token exchange (PKCE).
     * DocuSign returns origin_required_but_not_included if missing when CORS origins are configured.
     *
     * @return Origin header value
     */
    public static String getOrigin() {
        return DocusignConfig.getString("DOCUSIGN_ORIGIN", "http://localhost:2990");
    }

    /**
     * Gets the OAuth scopes
     * 
     * @return Space-separated list of scopes
     */
    public static String getScopes() {
        return SCOPES;
    }

    /**
     * Builds a complete authorization URL with query parameters
     * 
     * @param state Optional state parameter for CSRF protection
     * @return Complete authorization URL with query parameters
     */
    public static String buildAuthorizationUrl(String state) {
        StringBuilder url = new StringBuilder(getAuthorizationUrl());
        url.append("?response_type=code");
        url.append("&client_id=").append(getClientId());
        url.append("&redirect_uri=").append(getRedirectUri());
        url.append("&scope=").append(getScopes());
        if (state != null && !state.trim().isEmpty()) {
            url.append("&state=").append(state);
        }
        return url.toString();
    }
}

