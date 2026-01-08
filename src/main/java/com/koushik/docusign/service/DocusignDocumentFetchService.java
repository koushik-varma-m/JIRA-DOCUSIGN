package com.koushik.docusign.service;

import com.koushik.docusign.config.DocusignConfig;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

/**
 * Service to fetch the final signed document (combined PDF) from DocuSign.
 */
public class DocusignDocumentFetchService {

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

        String url = restBase + "/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId.trim() + "/documents/combined";
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
