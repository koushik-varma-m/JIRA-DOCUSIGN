package com.koushik.docusign.service;

import com.koushik.docusign.config.DocusignConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;

/**
 * Service to fetch envelope-level status from DocuSign.
 */
public class DocusignEnvelopeStatusService {

    private static final Gson GSON = new Gson();

    private final String accountId;
    private final String restBase;

    public DocusignEnvelopeStatusService() {
        this(readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi"),
                readCfg("DOCUSIGN_ACCOUNT_ID", true));
    }

    public DocusignEnvelopeStatusService(String restBase, String accountId) {
        this.restBase = (restBase != null && !restBase.trim().isEmpty()) ? restBase.trim() : readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi");
        this.accountId = (accountId != null && !accountId.trim().isEmpty()) ? accountId.trim() : readCfg("DOCUSIGN_ACCOUNT_ID", true);
    }

    /**
     * Fetch envelope status from DocuSign.
     * @param envelopeId DocuSign envelope ID
     * @param accessToken OAuth token (Bearer)
     * @return status string (sent, delivered, completed, declined, etc.)
     */
    public String getEnvelopeStatus(String envelopeId, String accessToken) throws Exception {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            throw new IllegalArgumentException("envelopeId is required");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        String url = restBase + "/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId.trim();
        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Bearer " + accessToken.trim());
            get.setHeader("Accept", "application/json");
            try (CloseableHttpResponse resp = client.execute(get)) {
                int code = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + " from DocuSign: " + body);
                }
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                if (json != null && json.has("status") && !json.get("status").isJsonNull()) {
                    return json.get("status").getAsString();
                }
                return "unknown";
            }
        }
    }

    private static String readCfg(String key, boolean required) {
        return readCfg(key, required, null);
    }

    private static String readCfg(String key, boolean required, String def) {
        if (required) {
            return DocusignConfig.getRequiredString(key);
        }
        return DocusignConfig.getString(key, def);
    }
}
