package com.koushik.docusign.service;

import com.koushik.docusign.config.DocusignConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service to fetch per-recipient signing status from DocuSign.
 */
public class DocusignRecipientStatusService {

    private static final Gson GSON = new Gson();

    private final String accountId;
    private final String restBase;

    public DocusignRecipientStatusService() {
        this(readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi"),
                readCfg("DOCUSIGN_ACCOUNT_ID", true));
    }

    public DocusignRecipientStatusService(String restBase, String accountId) {
        this.restBase = (restBase != null && !restBase.trim().isEmpty()) ? restBase.trim() : readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi");
        this.accountId = (accountId != null && !accountId.trim().isEmpty()) ? accountId.trim() : readCfg("DOCUSIGN_ACCOUNT_ID", true);
    }

    /**
     * Fetch recipient status for an envelope.
     *
     * @param envelopeId  DocuSign envelope ID
     * @param accessToken OAuth access token (Bearer)
     * @return ordered list of recipient statuses (by routingOrder)
     */
    public List<RecipientStatus> getRecipientStatuses(String envelopeId, String accessToken) throws Exception {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            throw new IllegalArgumentException("envelopeId is required");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken is required");
        }

        String url = restBase + "/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId.trim() + "/recipients";
        JsonObject json = httpGetJson(url, accessToken.trim());

        List<RecipientStatus> recipients = new ArrayList<>();
        // DocuSign groups recipients by type; we only care about signers for now
        if (json.has("signers") && json.get("signers").isJsonArray()) {
            JsonArray signers = json.getAsJsonArray("signers");
            for (JsonElement el : signers) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                String name = getString(s, "name");
                String email = getString(s, "email");
                String status = getString(s, "status");
                String routingOrder = getString(s, "routingOrder");
                recipients.add(new RecipientStatus(name, email, status, routingOrder));
            }
        }

        // Sort by routing order numeric to preserve signing sequence
        recipients.sort(Comparator.comparingInt(r -> parseIntSafe(r.getRoutingOrder(), Integer.MAX_VALUE)));
        return recipients;
    }

    private JsonObject httpGetJson(String url, String bearerToken) throws Exception {
        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Bearer " + bearerToken);
            get.setHeader("Accept", "application/json");

            try (CloseableHttpResponse resp = client.execute(get)) {
                int code = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + " from DocuSign: " + body);
                }
                return GSON.fromJson(body, JsonObject.class);
            }
        }
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    private int parseIntSafe(String val, int def) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return def;
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

    /**
     * Recipient status DTO.
     */
    public static class RecipientStatus {
        private final String name;
        private final String email;
        private final String status;
        private final String routingOrder;

        public RecipientStatus(String name, String email, String status, String routingOrder) {
            this.name = name;
            this.email = email;
            this.status = status;
            this.routingOrder = routingOrder;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        public String getStatus() {
            return status;
        }

        public String getRoutingOrder() {
            return routingOrder;
        }
    }
}
