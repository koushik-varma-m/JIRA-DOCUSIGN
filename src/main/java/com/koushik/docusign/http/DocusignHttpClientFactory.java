package com.koushik.docusign.http;

import com.koushik.docusign.config.DocusignConfig;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

/**
 * Centralizes Apache HttpClient construction with sane timeouts so plugin threads don't hang
 * on network calls (common cause of Jira instability under bad network/DNS conditions).
 */
public final class DocusignHttpClientFactory {

    private DocusignHttpClientFactory() {}

    public static CloseableHttpClient create() {
        int connectTimeoutMs = readInt("DOCUSIGN_HTTP_CONNECT_TIMEOUT_MS", 10_000);
        int socketTimeoutMs = readInt("DOCUSIGN_HTTP_SOCKET_TIMEOUT_MS", 30_000);
        int requestTimeoutMs = readInt("DOCUSIGN_HTTP_CONNECTION_REQUEST_TIMEOUT_MS", 5_000);

        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .setConnectionRequestTimeout(requestTimeoutMs)
                .build();

        return HttpClientBuilder.create()
                .setDefaultRequestConfig(cfg)
                .disableCookieManagement()
                .setUserAgent("jira-docusign-plugin")
                .build();
    }

    private static int readInt(String key, int def) {
        try {
            String v = DocusignConfig.getString(key, null);
            if (v == null) return def;
            v = v.trim();
            if (v.isEmpty()) return def;
            int n = Integer.parseInt(v);
            return n > 0 ? n : def;
        } catch (Exception ignore) {
            return def;
        }
    }
}

