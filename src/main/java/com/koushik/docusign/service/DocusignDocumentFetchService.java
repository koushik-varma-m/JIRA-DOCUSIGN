package com.koushik.docusign.service;

import com.koushik.docusign.config.DocusignConfig;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service to fetch the final signed document (combined PDF) from DocuSign.
 */
public class DocusignDocumentFetchService {

    private static final Gson GSON = new Gson();

    private final String accountId;
    private final String restBase;

    public DocusignDocumentFetchService() {
        this(readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi"),
                readCfg("DOCUSIGN_ACCOUNT_ID", true));
    }

    public DocusignDocumentFetchService(String restBase, String accountId) {
        this.restBase = (restBase != null && !restBase.trim().isEmpty()) ? restBase.trim() : readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi");
        this.accountId = (accountId != null && !accountId.trim().isEmpty()) ? accountId.trim() : readCfg("DOCUSIGN_ACCOUNT_ID", true);
    }

    /**
     * Fetch combined signed PDF for an envelope. Throws if envelope is not completed.
     *
     * @param envelopeId  envelope id
     * @param accessToken OAuth bearer token
     * @return PDF bytes
     */
    public byte[] fetchSignedPdf(String envelopeId, String accessToken) throws Exception {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            throw new IllegalArgumentException("envelopeId is required");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken is required");
        }

        // First, ensure envelope is completed
        DocusignEnvelopeStatusService statusService = new DocusignEnvelopeStatusService(restBase, accountId);
        String status = statusService.getEnvelopeStatus(envelopeId, accessToken);
        if (status == null || !status.equalsIgnoreCase("completed")) {
            throw new IllegalStateException("Envelope is not completed; current status: " + status);
        }

        return fetchDocumentPdf(envelopeId, "combined", accessToken);
    }

    public static final class EnvelopeDocument {
        public final String documentId;
        public final String name;
        public final String type;

        public EnvelopeDocument(String documentId, String name, String type) {
            this.documentId = documentId;
            this.name = name;
            this.type = type;
        }
    }

    /**
     * List envelope documents from DocuSign. The response includes non-content docs like "certificate";
     * callers should filter based on {@code type} or {@code documentId}.
     */
    public List<EnvelopeDocument> listEnvelopeDocuments(String envelopeId, String accessToken) throws Exception {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            throw new IllegalArgumentException("envelopeId is required");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        String url = restBase + "/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId.trim() + "/documents";
        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Bearer " + accessToken.trim());
            get.setHeader("Accept", "application/json");
            try (CloseableHttpResponse resp = client.execute(get)) {
                int code = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + " from DocuSign when listing envelope documents");
                }
                JsonObject obj = null;
                try {
                    obj = GSON.fromJson(body, JsonObject.class);
                } catch (Exception e) {
                    obj = null;
                }
                List<EnvelopeDocument> out = new ArrayList<>();
                if (obj == null) return out;
                JsonElement docsEl = obj.get("envelopeDocuments");
                if (docsEl == null || !docsEl.isJsonArray()) return out;
                JsonArray arr = docsEl.getAsJsonArray();
                for (JsonElement el : arr) {
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject d = el.getAsJsonObject();
                    String id = d.has("documentId") ? safeStr(d.get("documentId")) : null;
                    String name = d.has("name") ? safeStr(d.get("name")) : null;
                    String type = d.has("type") ? safeStr(d.get("type")) : null;
                    if (id == null || id.trim().isEmpty()) continue;
                    out.add(new EnvelopeDocument(id.trim(), name != null ? name : "", type != null ? type : ""));
                }
                return out;
            }
        }
    }

    /**
     * Fetch a signed PDF for a specific documentId. Pass {@code documentId="combined"} for the combined PDF.
     * Caller should ensure the envelope is completed (or accept DocuSign error responses).
     */
    public byte[] fetchDocumentPdf(String envelopeId, String documentId, String accessToken) throws Exception {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            throw new IllegalArgumentException("envelopeId is required");
        }
        if (documentId == null || documentId.trim().isEmpty()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        String url = restBase + "/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId.trim() + "/documents/" + documentId.trim();
        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Bearer " + accessToken.trim());
            get.setHeader("Accept", "application/pdf");
            try (CloseableHttpResponse resp = client.execute(get)) {
                int code = resp.getStatusLine().getStatusCode();
                byte[] body = EntityUtils.toByteArray(resp.getEntity());
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + " from DocuSign when downloading signed PDF");
                }
                return body;
            }
        }
    }

    private static String safeStr(JsonElement el) {
        if (el == null) return null;
        try {
            if (el.isJsonPrimitive()) return el.getAsString();
        } catch (Exception ignore) {
        }
        try {
            return el.toString();
        } catch (Exception ignore) {
            return null;
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
