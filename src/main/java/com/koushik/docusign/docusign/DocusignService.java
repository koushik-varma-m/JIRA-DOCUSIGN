package com.koushik.docusign.docusign;



import com.koushik.docusign.config.DocusignConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koushik.docusign.http.DocusignHttpClientFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;



public class DocusignService {

    // ====== Configuration (set as ENV or -D JVM props) ======
    // DOCUSIGN_ACCOUNT_ID      = DocuSign Account ID
    // Optional:
    // DOCUSIGN_REST_BASE       = https://demo.docusign.net/restapi
    // =======================================================



    private static final Gson GSON = new Gson();
    private static final Logger log = LoggerFactory.getLogger(DocusignService.class);



    private final String accountId;

    private final String restBase;



    public DocusignService() {

        this(readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi"),
                readCfg("DOCUSIGN_ACCOUNT_ID", true));

    }

    public DocusignService(String restBase, String accountId) {
        this.restBase = (restBase != null && !restBase.trim().isEmpty()) ? restBase.trim() : readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi");
        this.accountId = (accountId != null && !accountId.trim().isEmpty()) ? accountId.trim() : readCfg("DOCUSIGN_ACCOUNT_ID", true);
    }



    // -------- Public API --------



    public String sendEnvelope(String issueKey,

                               List<DocusignDocument> documents,

                               List<DocusignSigner> signers,
                               String accessToken) throws Exception {

        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken is required to send envelope");
        }



        JsonObject envelope = buildEnvelope(issueKey, documents, signers);
        org.slf4j.LoggerFactory.getLogger(DocusignService.class)
                .info("DocuSign envelope payload: {}", envelope.toString());



        String url = restBase + "/v2.1/accounts/" + accountId + "/envelopes";

        JsonObject resp = httpPostJson(url, accessToken, envelope);



        if (!resp.has("envelopeId")) {

            throw new RuntimeException("DocuSign response missing envelopeId: " + resp);

        }

        return resp.get("envelopeId").getAsString();

    }

    // -------- DTOs you will use from REST resource --------



    public static class DocusignDocument {

        public String filename;

        public String base64;      // base64 of file bytes (NOT base64url)

        public String extension;   // optional

        public String documentId;  // "1", "2", ...



        public DocusignDocument(String filename, String base64, String documentId) {

            this.filename = filename;

            this.base64 = base64;

            this.documentId = documentId;

        }

    }



    public static class DocusignSigner {

        public String name;

        public String email;

        public String recipientId;   // "1", "2", ...

        public String routingOrder;  // "1", "2", ...

        public String pageNumber;    // optional, defaults to 1

        public String xPosition;     // optional, defaults to 400

        public String yPosition;     // optional, defaults to 650
        public List<TabPosition> positions; // optional multiple positions



        public DocusignSigner(String name, String email, String recipientId, String routingOrder) {

            this(name, email, recipientId, routingOrder, null, null, null);

        }

        public DocusignSigner(String name, String email, String recipientId, String routingOrder,
                              String pageNumber, String xPosition, String yPosition) {
            this(name, email, recipientId, routingOrder, pageNumber, xPosition, yPosition, null);
        }

        public DocusignSigner(String name, String email, String recipientId, String routingOrder,
                              String pageNumber, String xPosition, String yPosition, List<TabPosition> positions) {

            this.name = name;

            this.email = email;

            this.recipientId = recipientId;

            this.routingOrder = routingOrder;

            this.pageNumber = pageNumber;

            this.xPosition = xPosition;

            this.yPosition = yPosition;
            this.positions = positions;

        }

    }

    public static class TabPosition {
        public String pageNumber;
        public String xPosition;
        public String yPosition;
        public String documentId; // optional

        public TabPosition(String pageNumber, String xPosition, String yPosition) {
            this.pageNumber = pageNumber;
            this.xPosition = xPosition;
            this.yPosition = yPosition;
            this.documentId = null;
        }

        public TabPosition(String pageNumber, String xPosition, String yPosition, String documentId) {
            this.pageNumber = pageNumber;
            this.xPosition = xPosition;
            this.yPosition = yPosition;
            this.documentId = documentId;
        }
    }



    // -------- Internal: Envelope JSON --------



    private JsonObject buildEnvelope(String issueKey,
                                    List<DocusignDocument> documents,
                                    List<DocusignSigner> signers) {

        String safeIssueKey = sanitize(issueKey);
        JsonObject env = new JsonObject();

        env.addProperty("emailSubject", "Please sign documents (Jira: " + safeIssueKey + ")");

        env.addProperty("status", "sent"); // "created" for draft

        // Optional: webhook (DocuSign eventNotification) for near-instant updates.
        // Use ngrok (or public Jira URL) and set DOCUSIGN_WEBHOOK_URL to something like:
        //   https://<your-tunnel-domain>/jira/rest/docusign/1.0/send/webhook
        String webhookBase = readCfg("DOCUSIGN_WEBHOOK_URL", false, null);
        if (webhookBase != null && !webhookBase.trim().isEmpty()) {
            String webhookSecret = com.koushik.docusign.config.DocusignConfig.getSecretString("DOCUSIGN_WEBHOOK_SECRET", null);
            String connectHmacKey = com.koushik.docusign.config.DocusignConfig.getSecretString("DOCUSIGN_CONNECT_HMAC_KEY", null);
            String base = webhookBase.trim();
            // Prefer mapping via envelope custom field (jiraIssueKey) so the webhook doesn't need issueKey in the URL.
            // You can enable issueKey-in-URL for local debugging via DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY=true.
            String url = base;
            String includeIssueKey = readCfg("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY", false, "false");
            boolean shouldIncludeIssueKey = "true".equalsIgnoreCase(includeIssueKey != null ? includeIssueKey.trim() : "");
            if (shouldIncludeIssueKey) {
                String sep = url.contains("?") ? "&" : "?";
                url = url + sep + "issueKey=" + encodeUrl(safeIssueKey);
            }
            // Prefer DocuSign Connect HMAC verification. A shared secret in the URL is a dev-friendly fallback,
            // but it may be logged by proxies/access logs. You can disable it via DOCUSIGN_WEBHOOK_INCLUDE_SECRET=false.
            String includeSecret = readCfg("DOCUSIGN_WEBHOOK_INCLUDE_SECRET", false, "true");
            boolean shouldIncludeSecret = "true".equalsIgnoreCase(includeSecret != null ? includeSecret.trim() : "");
            if (shouldIncludeSecret && (connectHmacKey == null || connectHmacKey.trim().isEmpty())) {
                if (webhookSecret != null && !webhookSecret.trim().isEmpty()) {
                    String sep = url.contains("?") ? "&" : "?";
                    url = url + sep + "secret=" + encodeUrl(webhookSecret.trim());
                }
            }

            JsonObject eventNotification = new JsonObject();
            eventNotification.addProperty("url", url);
            eventNotification.addProperty("loggingEnabled", true);
            eventNotification.addProperty("requireAcknowledgment", true);
            eventNotification.addProperty("useSoapInterface", false);
            eventNotification.addProperty("includeDocuments", false);
            eventNotification.addProperty("includeEnvelopeVoidReason", true);

            com.google.gson.JsonArray envelopeEvents = new com.google.gson.JsonArray();
            envelopeEvents.add(envelopeEvent("sent"));
            envelopeEvents.add(envelopeEvent("delivered"));
            envelopeEvents.add(envelopeEvent("completed"));
            envelopeEvents.add(envelopeEvent("declined"));
            envelopeEvents.add(envelopeEvent("voided"));
            eventNotification.add("envelopeEvents", envelopeEvents);

            com.google.gson.JsonArray recipientEvents = new com.google.gson.JsonArray();
            recipientEvents.add(recipientEvent("Delivered"));
            recipientEvents.add(recipientEvent("Completed"));
            recipientEvents.add(recipientEvent("Declined"));
            recipientEvents.add(recipientEvent("AuthenticationFailed"));
            eventNotification.add("recipientEvents", recipientEvents);

            env.add("eventNotification", eventNotification);

            // Also store issue key as envelope custom field (useful if query param is removed).
            JsonObject envelopeCustomFields = new JsonObject();
            com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
            JsonObject field = new JsonObject();
            field.addProperty("name", "jiraIssueKey");
            field.addProperty("value", safeIssueKey);
            fields.add(field);
            envelopeCustomFields.add("textCustomFields", fields);
            env.add("customFields", envelopeCustomFields);
        }



        // documents

        com.google.gson.JsonArray docs = new com.google.gson.JsonArray();

        for (DocusignDocument d : documents) {

            JsonObject doc = new JsonObject();
            doc.addProperty("documentBase64", d.base64);
            doc.addProperty("name", sanitize(d.filename));
            String ext = resolveFileExtension(d.filename, d.extension);
            if (ext != null && !ext.isEmpty()) {
                doc.addProperty("fileExtension", ext);
            }
            doc.addProperty("documentId", d.documentId);
            docs.add(doc);

        }

        env.add("documents", docs);



        // recipients/signers

        com.google.gson.JsonArray signerArr = new com.google.gson.JsonArray();

        for (DocusignSigner s : signers) {

            JsonObject signer = new JsonObject();
            signer.addProperty("email", sanitize(s.email));
            signer.addProperty("name", sanitize(s.name));
            signer.addProperty("recipientId", s.recipientId);
            signer.addProperty("routingOrder", s.routingOrder);



            // Add signature tabs - one signHere tab per document

            JsonObject tabs = new JsonObject();

            com.google.gson.JsonArray signHereTabs = new com.google.gson.JsonArray();
            int tabIndex = 0;

            // Add signature tabs.
            // - If tab positions specify a documentId, apply them to that document.
            // - Otherwise, treat them as belonging to the first document (legacy/preview behavior).
            // - For any document without explicit tabs, add a single default signature tab.
            List<TabPosition> tabList = (s.positions != null && !s.positions.isEmpty()) ? s.positions : null;

            for (int di = 0; di < documents.size(); di++) {
                DocusignDocument d = documents.get(di);
                if (d == null) continue;

                List<TabPosition> docPositions = null;
                if (tabList != null) {
                    for (TabPosition tp : tabList) {
                        if (tp == null) continue;
                        String tpDoc = tp.documentId != null ? tp.documentId.trim() : "";
                        String docId = d.documentId != null ? d.documentId.trim() : "";
                        boolean matches = (!tpDoc.isEmpty() && !docId.isEmpty() && tpDoc.equals(docId));
                        boolean legacyFirstDoc = (tpDoc.isEmpty() && di == 0);
                        if (matches || legacyFirstDoc) {
                            if (docPositions == null) docPositions = new ArrayList<>();
                            docPositions.add(tp);
                        }
                    }
                }

                if (docPositions != null && !docPositions.isEmpty()) {
                    for (TabPosition tp : docPositions) {
                        JsonObject signHere = new JsonObject();
                        signHere.addProperty("documentId", d.documentId);
                        signHere.addProperty("pageNumber", resolveOrDefault(tp.pageNumber, "1"));
                        signHere.addProperty("xPosition", resolveOrDefault(tp.xPosition, "400"));
                        signHere.addProperty("yPosition", resolveOrDefault(tp.yPosition, "650"));
                        signHere.addProperty("recipientId", s.recipientId);
                        // DocuSign uses tabLabel as a logical identifier; keep it unique so multiple tabs aren't overwritten.
                        signHere.addProperty("tabLabel", "jiraSignHere_" + s.recipientId + "_" + d.documentId + "_" + (tabIndex++));
                        signHereTabs.add(signHere);
                    }
                } else {
                    JsonObject signHere = new JsonObject();
                    signHere.addProperty("documentId", d.documentId);
                    signHere.addProperty("pageNumber", resolveOrDefault(s.pageNumber, "1"));
                    signHere.addProperty("xPosition", resolveOrDefault(s.xPosition, "400"));
                    signHere.addProperty("yPosition", resolveOrDefault(s.yPosition, "650"));
                    signHere.addProperty("recipientId", s.recipientId);
                    signHere.addProperty("tabLabel", "jiraSignHere_" + s.recipientId + "_" + d.documentId + "_" + (tabIndex++));
                    signHereTabs.add(signHere);
                }
            }

            tabs.add("signHereTabs", signHereTabs);

            signer.add("tabs", tabs);

            signerArr.add(signer);

        }



        JsonObject recipients = new JsonObject();

        recipients.add("signers", signerArr);

        env.add("recipients", recipients);



        return env;

    }

    private JsonObject envelopeEvent(String statusCode) {
        JsonObject ev = new JsonObject();
        ev.addProperty("envelopeEventStatusCode", statusCode);
        ev.addProperty("includeDocuments", "false");
        return ev;
    }

    private JsonObject recipientEvent(String statusCode) {
        JsonObject ev = new JsonObject();
        ev.addProperty("recipientEventStatusCode", statusCode);
        ev.addProperty("includeDocuments", "false");
        return ev;
    }

    private String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value != null ? value : "", "UTF-8");
        } catch (Exception e) {
            return value != null ? value : "";
        }
    }

    private String resolveOrDefault(String value, String defVal) {
        if (value == null || value.trim().isEmpty()) {
            return defVal;
        }
        return value.trim();
    }



    // -------- Internal: HTTP helpers --------



    private JsonObject httpPostJson(String url, String bearerToken, JsonObject jsonBody) throws Exception {

        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {

            HttpPost post = new HttpPost(url);

            post.setHeader("Content-Type", "application/json");

            if (bearerToken != null) {

                post.setHeader("Authorization", "Bearer " + bearerToken);

            }

            post.setEntity(new StringEntity(GSON.toJson(jsonBody), ContentType.APPLICATION_JSON));



            try (CloseableHttpResponse resp = client.execute(post)) {

                int code = resp.getStatusLine().getStatusCode();

                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);



                if (code < 200 || code >= 300) {

                    log.warn("DocuSign API error response (HTTP {}): {}", code, body);

                    throw new RuntimeException("HTTP " + code + " from DocuSign: " + body);

                }

                return GSON.fromJson(body, JsonObject.class);

            }

        }

    }



    // -------- Internal: config --------



    private static String readCfg(String key, boolean required) {

        return readCfg(key, required, null);

    }



    private static String readCfg(String key, boolean required, String def) {
        if (required) {
            String v = DocusignConfig.getString(key, null);
            if (v == null || v.trim().isEmpty()) {
                throw new IllegalStateException("Missing config: " + key + " (set as plugin setting, env var, or -D" + key + ")");
            }
            return v.trim();
        }
        return DocusignConfig.getString(key, def);
    }

    private String resolveFileExtension(String filename, String extension) {
        String ext = extension;
        if (ext == null || ext.trim().isEmpty()) {
            String name = filename != null ? filename : "";
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1);
            }
        }
        if (ext == null) return null;
        ext = ext.trim();
        if (ext.startsWith(".")) ext = ext.substring(1);
        ext = ext.toLowerCase(Locale.ROOT);
        // DocuSign expects only the extension token (e.g., "pdf", "docx").
        ext = ext.replaceAll("[^a-z0-9]", "");
        if (ext.isEmpty()) return null;
        if (ext.length() > 10) ext = ext.substring(0, 10);
        return ext;
    }

    /**
     * Sanitize strings before sending to DocuSign:
     * - remove control chars (except newline/tab handled separately)
     * - replace \r, \n, \t with a single space
     * - trim
     */
    private String sanitize(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        cleaned = cleaned.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return cleaned.trim();
    }

}
