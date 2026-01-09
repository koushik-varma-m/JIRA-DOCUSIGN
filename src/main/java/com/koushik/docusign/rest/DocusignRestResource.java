package com.koushik.docusign.rest;

import com.atlassian.jira.bc.issue.properties.IssuePropertyService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.entity.property.EntityPropertyOptions;
import com.atlassian.jira.entity.property.EntityPropertyService.PropertyResult;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.AttachmentManager;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugins.rest.common.security.AnonymousAllowed;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koushik.docusign.config.DocusignConfig;
import com.koushik.docusign.docusign.DocusignService;
import com.koushik.docusign.docusign.DocusignService.DocusignDocument;
import com.koushik.docusign.docusign.DocusignService.DocusignSigner;
import com.koushik.docusign.persistence.DocusignAoStore;
import com.koushik.docusign.service.DocusignDocumentDownloadService;
import com.koushik.docusign.service.DocusignDocumentFetchService;
import com.koushik.docusign.service.DocusignRecipientStatusService;
import com.koushik.docusign.service.DocusignRecipientStatusService.RecipientStatus;
import com.koushik.docusign.oauth.DocusignTokenStore;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import com.koushik.docusign.http.DocusignHttpClientFactory;

/**
 * REST resource for sending Jira attachments to DocuSign for e-signature.
 */
@Path("/send")
public class DocusignRestResource {

    private static final Logger log = LoggerFactory.getLogger(DocusignRestResource.class);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");
    private static final String ISSUE_PROPERTY_ENTITY_NAME = "IssueProperty";

    private final IssueManager issueManager = ComponentAccessor.getIssueManager();
    private final UserManager userManager = ComponentAccessor.getUserManager();
    private final IssuePropertyService issuePropertyService = ComponentAccessor.getComponent(IssuePropertyService.class);
    private final JsonEntityPropertyManager jsonEntityPropertyManager = ComponentAccessor.getComponent(JsonEntityPropertyManager.class);
    private final JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
    private final PermissionManager permissionManager = ComponentAccessor.getPermissionManager();
    private final DocusignDocumentDownloadService documentDownloadService = new DocusignDocumentDownloadService();
    private static final Gson GSON = new Gson();
    private final String restBase = readCfg("DOCUSIGN_REST_BASE", false, "https://demo.docusign.net/restapi");
    private final String accountId = readCfg("DOCUSIGN_ACCOUNT_ID", false, null);
    @Context
    private HttpServletRequest httpRequest;
    private HttpSession getSession() {
        return httpRequest != null ? httpRequest.getSession(false) : null;
    }

    private String resolveAccessTokenForUser(ApplicationUser user) {
        return DocusignTokenStore.getValidAccessToken(user, getSession());
    }

    private String resolveRestBaseForUser(ApplicationUser user) {
        try {
            String override = DocusignTokenStore.getRestBaseOverride(user);
            if (override != null && !override.trim().isEmpty()) {
                return override.trim();
            }
        } catch (Exception ignore) {
        }
        return restBase;
    }

    private String resolveAccountIdForUser(ApplicationUser user) {
        try {
            String override = DocusignTokenStore.getAccountIdOverride(user);
            if (override != null && !override.trim().isEmpty()) {
                return override.trim();
            }
        } catch (Exception ignore) {
        }
        return accountId;
    }

    private String requireAccountIdForUser(ApplicationUser user) {
        String aid = resolveAccountIdForUser(user);
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalStateException("DocuSign account context is missing. Reconnect DocuSign to discover your account, or set DOCUSIGN_ACCOUNT_ID in plugin settings.");
        }
        return aid.trim();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendDocument(SendRequest request) {
        try {
            if (request == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Request body is required"))
                        .build();
            }
            if (request.getIssueKey() == null || request.getIssueKey().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("issueKey is required and cannot be empty"))
                        .build();
            }
            if (request.getAttachmentIds() == null || request.getAttachmentIds().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("attachmentIds is required and cannot be empty"))
                        .build();
            }
            if (request.getSigners() == null || request.getSigners().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("signers is required and cannot be empty"))
                        .build();
            }

            Issue issue = issueManager.getIssueObject(request.getIssueKey());
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Invalid issue key: " + request.getIssueKey()))
                        .build();
            }

            ApplicationUser currentUser = authContext != null ? authContext.getLoggedInUser() : null;
            if (currentUser == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("Authentication required to send DocuSign envelopes."))
                        .build();
            }
            if (!permissionManager.hasPermission(Permissions.EDIT_ISSUE, issue, currentUser)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(errorJson("You need Edit Issue permission on this issue to send DocuSign envelopes."))
                        .build();
            }

            AttachmentManager attachmentManager = ComponentAccessor.getAttachmentManager();
            List<Attachment> allAttachments = attachmentManager.getAttachments(issue);
            if (allAttachments == null || allAttachments.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Issue has no attachments"))
                        .build();
            }

            List<Long> selectedAttachmentIds = request.getAttachmentIds();
            List<Attachment> selectedAttachments = allAttachments.stream()
                    .filter(attachment -> selectedAttachmentIds.contains(attachment.getId()))
                    .collect(Collectors.toList());
            if (selectedAttachments.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("None of the specified attachment IDs were found on this issue"))
                        .build();
            }

            List<DocusignDocument> documents = new ArrayList<>();
            int docId = 1;
            for (Attachment attachment : selectedAttachments) {
                try {
                    byte[] fileBytes = attachmentManager.streamAttachmentContent(attachment, (InputStream inputStream) -> {
                        try {
                            return IOUtils.toByteArray(inputStream);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read attachment stream: " + attachment.getFilename(), e);
                        }
                    });
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    String filename = sanitize(attachment.getFilename());
                    documents.add(new DocusignDocument(filename, base64, String.valueOf(docId++)));
                } catch (Exception e) {
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(errorJson("Failed to read attachment: " + attachment.getFilename() + " - " + e.getMessage()))
                            .build();
                }
            }

            // Map selected Jira attachment IDs to DocuSign documentIds so UI-picked positions can target the right document.
            Map<Long, String> attachmentIdToDocumentId = new HashMap<>();
            try {
                for (int i = 0; i < selectedAttachments.size() && i < documents.size(); i++) {
                    Attachment a = selectedAttachments.get(i);
                    DocusignDocument d = documents.get(i);
                    if (a == null || d == null) continue;
                    if (a.getId() == null) continue;
                    String docIdStr = d.documentId != null ? d.documentId : String.valueOf(i + 1);
                    attachmentIdToDocumentId.put(a.getId(), docIdStr);
                }
            } catch (Exception ignore) {
            }

            List<DocusignSigner> signers;
            try {
                signers = convertMixedSigners(request.getSigners(), attachmentIdToDocumentId);
            } catch (IllegalArgumentException iae) {
                String msg = iae.getMessage() != null ? iae.getMessage() : "Invalid signer";
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson(msg))
                        .build();
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "Invalid signer";
                log.warn("Signer conversion failed for issue {}: {}", request.getIssueKey(), msg);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(errorJson(msg))
                        .build();
            }

            String accessToken = resolveAccessTokenForUser(currentUser);
            if (accessToken == null || accessToken.trim().isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("DocuSign is not connected or token expired. Please click 'Connect DocuSign' and try again."))
                        .build();
            }

            String resolvedRestBase = resolveRestBaseForUser(currentUser);
            String resolvedAccountId = requireAccountIdForUser(currentUser);
            DocusignService docusignService = new DocusignService(resolvedRestBase, resolvedAccountId);
            String envelopeId;
            String safeIssueKey = sanitize(issue.getKey());
            String persistenceWarning = null;
            try {
                envelopeId = docusignService.sendEnvelope(safeIssueKey, documents, signers, accessToken);
                // Persist to AO (source of truth) best-effort. Never block UI cache writes on AO startup/reload timing.
                try {
                    List<DocusignAoStore.SignerMeta> meta = new ArrayList<>();
                    try {
                        if (request.getSigners() != null) {
                            for (SignerInput si : request.getSigners()) {
                                if (si == null) continue;
                                meta.add(new DocusignAoStore.SignerMeta(si.getType(), si.getValue()));
                            }
                        }
                    } catch (Exception ignore) {
                        meta = new ArrayList<>();
                    }
                    DocusignAoStore.recordSentEnvelope(issue, envelopeId, "sent", currentUser, request.getAttachmentIds(), documents, signers, request, meta);
                } catch (Exception aoEx) {
                    String msg = aoEx.getMessage() != null ? aoEx.getMessage() : "Failed to persist DocuSign metadata (AO).";
                    log.error("DocuSign envelope {} sent but AO persistence failed for issue {}: {}", envelopeId, issue.getKey(), msg);
                    persistenceWarning = msg;
                }
                // Always try to store minimal issue properties so polling/status refresh can work.
                try {
                    storeEnvelopeMeta(issue, envelopeId, "sent");
                } catch (Exception propEx) {
                    String msg = propEx.getMessage() != null ? propEx.getMessage() : "Failed to persist envelope metadata to issue properties.";
                    log.error("DocuSign envelope {} sent but issue property persistence failed for issue {}: {}", envelopeId, issue.getKey(), msg);
                    if (persistenceWarning == null) persistenceWarning = msg;
                }
                try {
                    storeInitialUiState(issue, signers);
                } catch (Exception ignore) {
                }
                try {
                    storeRecipientStatus(issue, envelopeId, accessToken, resolvedRestBase, resolvedAccountId);
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                log.error("Failed to send envelope for issue {}: {}", issue.getKey(), e.getMessage(), e);
                String msg = friendlyDocuSignError(e, "Failed to send envelope");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(errorJson(msg))
                        .build();
            }

            JsonObject out = new JsonObject();
            out.addProperty("envelopeId", envelopeId);
            out.addProperty("status", "sent");
            if (persistenceWarning != null && !persistenceWarning.trim().isEmpty()) {
                out.addProperty("persistenceWarning", persistenceWarning);
            }
            return Response.ok(out.toString()).build();

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Internal server error";
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson(errorMsg))
                    .build();
        }
    }

    private void storeRecipientStatus(Issue issue, String envelopeId, String accessToken, String restBase, String accountId) throws Exception {
        storeRecipientStatus(issue, envelopeId, accessToken, restBase, accountId, null);
    }

    private void storeRecipientStatus(Issue issue, String envelopeId, String accessToken, String restBase, String accountId, List<RecipientStatus> prefetched) throws Exception {
        if (issue == null || envelopeId == null || accessToken == null) {
            return;
        }
        if (accountId == null || accountId.trim().isEmpty()) {
            return;
        }
        List<RecipientStatus> statuses = prefetched != null ? prefetched : new DocusignRecipientStatusService(restBase, accountId).getRecipientStatuses(envelopeId, accessToken);
        JsonArray arr = new JsonArray();
        for (RecipientStatus s : statuses) {
            JsonObject obj = new JsonObject();
            obj.addProperty("email", s.getEmail());
            obj.addProperty("name", s.getName());
            obj.addProperty("status", s.getStatus());
            arr.add(obj);
        }
        String newValue = GSON.toJson(arr);
        ApplicationUser user = resolveUser();
        String key = "docusign.signers";

        PropertyResult existing = issuePropertyService.getProperty(user, issue.getId(), key);
        if (existing != null && existing.getEntityProperty().isDefined()) {
            String oldValue = existing.getEntityProperty().get().getValue();
            if (newValue.equals(oldValue)) {
                return;
            }
        }

        setIssuePropertyJson(user, issue, key, arr.toString());
        try {
            DocusignAoStore.recordStatusUpdate(issue.getKey(), envelopeId, null, statuses, "recipients.refresh", null);
        } catch (Exception ignore) {
        }
    }

    private void storeEnvelopeMeta(Issue issue, String envelopeId, String status) {
        if (issue == null || envelopeId == null || status == null) {
            return;
        }
        ApplicationUser user = resolveUser();
        String safeEnvelopeId = sanitize(envelopeId);
        String safeStatus = sanitize(status);

        setIssuePropertyJson(user, issue, "docusign.envelopeId", wrapValueJson(safeEnvelopeId));
        setIssuePropertyJson(user, issue, "docusign.envelopeStatus", wrapValueJson(safeStatus));
        upsertEnvelopeHistory(user, issue, safeEnvelopeId, safeStatus, null);
    }

    private void upsertEnvelopeHistory(ApplicationUser actor, Issue issue, String envelopeId, String status, EntityPropertyOptions options) {
        if (issue == null || envelopeId == null || envelopeId.trim().isEmpty()) return;
        String safeId = sanitize(envelopeId);
        String safeStatus = status != null ? sanitize(status) : "";
        long now = System.currentTimeMillis();

        JsonArray arr = new JsonArray();
        String raw = null;
        try {
            raw = readIssueProperty(issue, "docusign.envelopes");
            if (raw != null && !raw.trim().isEmpty()) {
                JsonElement el = JsonParser.parseString(raw);
                if (el != null && el.isJsonArray()) {
                    arr = el.getAsJsonArray();
                }
            }
        } catch (Exception ignore) {
            arr = new JsonArray();
        }

        boolean found = false;
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String id = obj.has("envelopeId") && obj.get("envelopeId").isJsonPrimitive() ? obj.get("envelopeId").getAsString() : null;
            if (id != null && id.equals(safeId)) {
                String oldStatus = obj.has("status") && obj.get("status").isJsonPrimitive() ? obj.get("status").getAsString() : "";
                if (!safeStatus.equals(oldStatus)) {
                    obj.addProperty("status", safeStatus);
                }
                obj.addProperty("updatedAtMs", now);
                found = true;
                break;
            }
        }
        if (!found) {
            JsonObject obj = new JsonObject();
            obj.addProperty("envelopeId", safeId);
            obj.addProperty("status", safeStatus);
            obj.addProperty("sentAtMs", now);
            obj.addProperty("updatedAtMs", now);
            arr.add(obj);
        }

        // Keep only the most recent N entries to stay within Jira issue property size limits.
        int maxEntries = 15;
        while (arr.size() > maxEntries) {
            arr.remove(0);
        }

        String next = arr.toString();
        if (raw != null && raw.trim().equals(next)) {
            return;
        }
        setIssuePropertyJson(actor, issue, "docusign.envelopes", next, options);
    }

    private String readIssueProperty(Issue issue, String key) {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        PropertyResult existing = issuePropertyService.getProperty(user, issue.getId(), key);
        if (existing == null || !existing.getEntityProperty().isDefined()) {
            return null;
        }
        String raw = existing.getEntityProperty().get().getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return el.getAsString();
            }
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("value") && obj.get("value").isJsonPrimitive()) {
                    return obj.get("value").getAsString();
                }
            }
            if (el.isJsonPrimitive()) {
                return el.getAsJsonPrimitive().getAsString();
            }
            return el.toString();
        } catch (Exception e) {
            // ignore and return raw
        }
        return raw;
    }

    private void storeInitialUiState(Issue issue, List<DocusignService.DocusignSigner> signers) {
        if (issue == null || signers == null || signers.isEmpty()) {
            return;
        }
        JsonArray arr = new JsonArray();
        for (int i = 0; i < signers.size(); i++) {
            DocusignService.DocusignSigner s = signers.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("name", s.name);
            obj.addProperty("email", s.email);
            obj.addProperty("routingOrder", s.routingOrder);
            obj.addProperty("uiStatus", i == 0 ? "CURRENT" : "PENDING");
            arr.add(obj);
        }
        ApplicationUser user = resolveUser();
        setIssuePropertyJson(user, issue, "docusign.signerUiState", arr.toString());
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTokenStatus() {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        boolean connected = DocusignTokenStore.isConnected(user, getSession());
        Long expiresAtVal = DocusignTokenStore.getExpiresAtMs(user, getSession());
        JsonObject obj = new JsonObject();
        obj.addProperty("connected", connected);
        if (expiresAtVal != null) {
            obj.addProperty("expiresAt", expiresAtVal);
        } else {
            obj.add("expiresAt", null);
        }
        return Response.ok(obj.toString()).build();
    }

    @POST
    @Path("/auth/disconnect")
    @Produces(MediaType.APPLICATION_JSON)
    public Response disconnect() {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(errorJson("Login required"))
                    .build();
        }
        DocusignTokenStore.disconnect(user, getSession());
        return Response.ok("{\"ok\":true}").build();
    }

    public static class ClearStateRequest {
        private String issueKey;
        public String getIssueKey() { return issueKey; }
        public void setIssueKey(String issueKey) { this.issueKey = issueKey; }
    }

    @POST
    @Path("/state/clear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearIssueState(ClearStateRequest req) {
        try {
            if (req == null || req.getIssueKey() == null || req.getIssueKey().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("issueKey is required"))
                        .build();
            }
            Issue issue = issueManager.getIssueObject(req.getIssueKey().trim());
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Invalid issue key: " + req.getIssueKey()))
                        .build();
            }
            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            if (user == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("Login required"))
                        .build();
            }
            if (!permissionManager.hasPermission(Permissions.EDIT_ISSUE, issue, user)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(errorJson("You need Edit Issue permission to clear DocuSign state."))
                        .build();
            }

            String[] keys = new String[] {
                    "docusign.envelopeId",
                    "docusign.envelopeStatus",
                    "docusign.envelopes",
                    "docusign.signers",
                    "docusign.signerUiState",
                    "docusign.signedAttached"
            };
            for (String k : keys) {
                try {
                    jsonEntityPropertyManager.delete(ISSUE_PROPERTY_ENTITY_NAME, issue.getId(), k);
                } catch (Exception ignore) {
                }
            }
            try {
                DocusignAoStore.clearActiveEnvelope(issue.getKey());
            } catch (Exception ignore) {
            }
            return Response.ok("{\"ok\":true}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson("Failed to clear state: " + (e.getMessage() != null ? e.getMessage() : "Unknown error")))
                    .build();
        }
    }

    @GET
    @Path("/diag")
    @Produces(MediaType.APPLICATION_JSON)
    public Response diagnostics() {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(errorJson("Login required")).build();
        }
        if (!permissionManager.hasPermission(Permissions.ADMINISTER, user)) {
            return Response.status(Response.Status.FORBIDDEN).entity(errorJson("Admin permission required")).build();
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("webhookUrlSet", DocusignConfig.getString("DOCUSIGN_WEBHOOK_URL", "").trim().length() > 0);
        obj.addProperty("webhookRequireAuth", "true".equalsIgnoreCase(DocusignConfig.getString("DOCUSIGN_WEBHOOK_REQUIRE_AUTH", "false")));
        obj.addProperty("webhookIncludeSecret", "true".equalsIgnoreCase(DocusignConfig.getString("DOCUSIGN_WEBHOOK_INCLUDE_SECRET", "true")));
        obj.addProperty("connectHmacKeySet", DocusignConfig.getSecretString("DOCUSIGN_CONNECT_HMAC_KEY", "").trim().length() > 0);
        obj.addProperty("webhookSecretSet", DocusignConfig.getSecretString("DOCUSIGN_WEBHOOK_SECRET", "").trim().length() > 0);
        obj.addProperty("clientIdSet", DocusignConfig.getString("DOCUSIGN_CLIENT_ID", "").trim().length() > 0);
        obj.addProperty("accountIdSet", DocusignConfig.getString("DOCUSIGN_ACCOUNT_ID", "").trim().length() > 0);
        obj.addProperty("oauthBase", DocusignConfig.getString("DOCUSIGN_OAUTH_BASE", ""));
        obj.addProperty("restBase", DocusignConfig.getString("DOCUSIGN_REST_BASE", ""));
        try {
            obj.addProperty("issuePropertyMaxLen", jsonEntityPropertyManager != null ? jsonEntityPropertyManager.getMaximumValueLength() : -1);
        } catch (Exception e) {
            obj.addProperty("issuePropertyMaxLen", -1);
        }
        return Response.ok(obj.toString()).build();
    }

    @GET
    @Path("/state")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getIssueState(@QueryParam("issueKey") String issueKey) {
        try {
            if (issueKey == null || issueKey.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("issueKey is required"))
                        .build();
            }
            Issue issue = issueManager.getIssueObject(issueKey);
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Invalid issue key: " + issueKey))
                        .build();
            }

            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            if (user == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("Login required"))
                        .build();
            }
            if (!permissionManager.hasPermission(Permissions.BROWSE, issue, user)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(errorJson("You don't have permission to view this issue"))
                        .build();
            }

            // Prefer AO (source of truth). Fall back to issue properties if AO isn't available yet.
            try {
                JsonObject aoState = DocusignAoStore.loadActiveIssueState(issueKey);
                if (aoState != null) {
                    return Response.ok(aoState.toString()).build();
                }
            } catch (Exception ignore) {
            }

            String envelopeId = sanitizeEnvelopeId(readIssueProperty(issue, "docusign.envelopeId"));
            String envelopeStatus = readIssueProperty(issue, "docusign.envelopeStatus");

            JsonArray signerUi = new JsonArray();
            try {
                String raw = readIssueProperty(issue, "docusign.signerUiState");
                if (raw != null && !raw.trim().isEmpty()) {
                    JsonElement el = JsonParser.parseString(raw);
                    if (el != null && el.isJsonArray()) {
                        signerUi = el.getAsJsonArray();
                    }
                }
            } catch (Exception ignore) {
                signerUi = new JsonArray();
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("issueKey", issue.getKey());
            resp.addProperty("envelopeId", envelopeId != null ? envelopeId : "");
            resp.addProperty("envelopeStatus", envelopeStatus != null ? envelopeStatus : "");
            resp.add("signerUiState", signerUi);
            return Response.ok(resp.toString()).build();
        } catch (Exception e) {
            log.error("Failed to load issue state", e);
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to load issue state";
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson(msg))
                    .build();
        }
    }

    @GET
    @Path("/state/history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getIssueHistory(@QueryParam("issueKey") String issueKey, @QueryParam("limit") Integer limit) {
        try {
            if (issueKey == null || issueKey.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("issueKey is required"))
                        .build();
            }
            Issue issue = issueManager.getIssueObject(issueKey);
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Invalid issue key: " + issueKey))
                        .build();
            }

            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            if (user == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("Login required"))
                        .build();
            }
            if (!permissionManager.hasPermission(Permissions.BROWSE, issue, user)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(errorJson("You don't have permission to view this issue"))
                        .build();
            }

            int n = (limit != null) ? limit.intValue() : 15;
            JsonArray history = null;
            try {
                history = DocusignAoStore.loadIssueHistory(issueKey, n);
            } catch (Exception ignore) {
                history = null;
            }
            // Fall back to the issue-property cache when AO isn't available yet or has no entries.
            if (history == null || history.size() == 0) {
                try {
                    JsonArray propHistory = loadIssuePropertyHistory(issue, n);
                    if (propHistory != null && propHistory.size() > 0) {
                        history = propHistory;
                    }
                } catch (Exception ignore) {
                }
            }
            if (history == null) history = new JsonArray();

            JsonObject resp = new JsonObject();
            resp.addProperty("issueKey", issue.getKey());
            resp.add("history", history);
            return Response.ok(resp.toString()).build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to load issue history";
            log.error("Failed to load AO envelope history for issue {}", issueKey, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson(msg))
                    .build();
        }
    }

    private JsonArray loadIssuePropertyHistory(Issue issue, int limit) {
        if (issue == null) return null;
        int n = (limit <= 0 || limit > 50) ? 15 : limit;
        String raw = null;
        try {
            raw = readIssueProperty(issue, "docusign.envelopes");
        } catch (Exception ignore) {
            raw = null;
        }
        if (raw == null || raw.trim().isEmpty()) return null;

        JsonArray arr;
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (el == null || !el.isJsonArray()) return null;
            arr = el.getAsJsonArray();
        } catch (Exception e) {
            return null;
        }
        if (arr.size() == 0) return null;

        // Convert to newest-first (AO endpoint semantics) and limit size.
        JsonArray out = new JsonArray();
        for (int i = arr.size() - 1; i >= 0 && out.size() < n; i--) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) continue;
            out.add(el.getAsJsonObject());
        }
        return out;
    }

    /**
     * DocuSign Connect / eventNotification webhook receiver.
     *
     * Note: This must allow anonymous access (DocuSign won't have a Jira session cookie).
     * For security, configure DOCUSIGN_CONNECT_HMAC_KEY (recommended) so DocuSign signs webhook payloads.
     */
    @POST
    @Path("/webhook")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @AnonymousAllowed
    public Response webhook(@QueryParam("issueKey") String issueKeyParam, @QueryParam("secret") String secretParam) {
        byte[] payload;
        try {
            if (httpRequest == null) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(errorJson("No request context"))
                        .build();
            }
            payload = readWebhookPayloadBytes(httpRequest);
        } catch (Exception e) {
            log.warn("DocuSign webhook: failed to read request body: {}", e.getMessage());
            // Return 2xx so DocuSign doesn't aggressively retry on transient read/stream errors.
            return Response.ok("{\"ok\":true,\"ignored\":true,\"reason\":\"read_failed\"}").build();
        }

        boolean trusted = false;

        String requireAuth = readCfg("DOCUSIGN_WEBHOOK_REQUIRE_AUTH", false, "false");
        boolean mustBeTrusted = "true".equalsIgnoreCase(requireAuth != null ? requireAuth.trim() : "");

        String hmacKey = null;
        try {
            hmacKey = readSecretCfg("DOCUSIGN_CONNECT_HMAC_KEY", false, null);
        } catch (Exception ignore) {
            hmacKey = null;
        }
        boolean hmacConfigured = hmacKey != null && !hmacKey.trim().isEmpty();

        String expectedSecret = readSecretCfg("DOCUSIGN_WEBHOOK_SECRET", false, null);
        boolean secretConfigured = expectedSecret != null && !expectedSecret.trim().isEmpty();

        if (mustBeTrusted && !hmacConfigured && !secretConfigured) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(errorJson("Webhook auth is required (configure DOCUSIGN_CONNECT_HMAC_KEY or DOCUSIGN_WEBHOOK_SECRET)"))
                    .build();
        }

        // Optional verification via HMAC key (recommended)
        try {
            if (hmacConfigured) {
                String sig1 = headerTrim("X-DocuSign-Signature-1");
                String sig2 = headerTrim("X-DocuSign-Signature-2");
                boolean hasSig = (sig1 != null && !sig1.isEmpty()) || (sig2 != null && !sig2.isEmpty());
                if (hasSig) {
                    String expected256 = computeHmacBase64(payload, hmacKey.trim(), "HmacSHA256");
                    String expected1 = computeHmacBase64(payload, hmacKey.trim(), "HmacSHA1");
                    boolean ok = (sig1 != null && (constantTimeEquals(expected256, sig1) || constantTimeEquals(expected1, sig1)))
                            || (sig2 != null && (constantTimeEquals(expected256, sig2) || constantTimeEquals(expected1, sig2)));
                    if (!ok) {
                        log.warn("DocuSign webhook: invalid signature (sig1={}, sig2={})", sig1, sig2);
                        return Response.status(Response.Status.UNAUTHORIZED)
                                .entity(errorJson("Invalid DocuSign signature"))
                                .build();
                    }
                    trusted = true;
                }
            }
        } catch (Exception e) {
            log.warn("DocuSign webhook: signature verification failed: {}", e.getMessage());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(errorJson("Signature verification failed"))
                    .build();
        }

        // Optional shared secret (fallback): only enforce when HMAC wasn't validated.
        if (!trusted && secretConfigured) {
            String provided = sanitize(secretParam);
            if (provided == null || !constantTimeEquals(expectedSecret.trim(), provided)) {
                log.warn("DocuSign webhook: invalid secret");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("Invalid webhook secret"))
                        .build();
            }
            trusted = true;
        }

        // Production hardening: optionally require at least one verification mechanism.
        if (mustBeTrusted && !trusted) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(errorJson("Webhook auth is required (configure DOCUSIGN_CONNECT_HMAC_KEY or DOCUSIGN_WEBHOOK_SECRET)"))
                    .build();
        }

        ConnectWebhookEvent evt;
        try {
            evt = parseConnectWebhook(payload);
        } catch (Exception e) {
            log.warn("DocuSign webhook: failed to parse payload as Connect XML: {}", e.getMessage());
            return Response.ok("{\"ok\":true,\"ignored\":true}").build();
        }

        // issueKey resolution:
        // - When request is authenticated (HMAC/secret), prefer envelope custom field (jiraIssueKey) to prevent spoofing.
        // - Only fall back to query param for dev/untrusted or missing custom field.
        String fromPayload = sanitize(evt.issueKey);
        String fromQuery = sanitize(issueKeyParam);
        String fromSubject = sanitize(extractIssueKey(evt.subject));
        String fromAo = null;
        if (trusted) {
            try {
                fromAo = sanitize(DocusignAoStore.findIssueKeyByEnvelopeId(sanitizeEnvelopeId(evt.envelopeId)));
            } catch (Exception ignore) {
                fromAo = null;
            }
        }
        String issueKey;
        if (trusted) {
            issueKey = (fromPayload != null && !fromPayload.isEmpty()) ? fromPayload : null;
            if ((issueKey == null || issueKey.isEmpty()) && fromSubject != null && !fromSubject.isEmpty()) {
                issueKey = fromSubject;
            }
            if ((issueKey == null || issueKey.isEmpty()) && fromAo != null && !fromAo.isEmpty()) {
                issueKey = fromAo;
            }
            // Only accept issueKey from query param when explicitly enabled for debugging.
            String includeIssueKey = readCfg("DOCUSIGN_WEBHOOK_INCLUDE_ISSUEKEY", false, "false");
            boolean allowFromQuery = "true".equalsIgnoreCase(includeIssueKey != null ? includeIssueKey.trim() : "");
            if (allowFromQuery && (issueKey == null || issueKey.isEmpty()) && fromQuery != null && !fromQuery.isEmpty()) {
                issueKey = fromQuery;
            }
        } else {
            issueKey = (fromQuery != null && !fromQuery.isEmpty()) ? fromQuery : null;
            if ((issueKey == null || issueKey.isEmpty()) && fromPayload != null && !fromPayload.isEmpty()) {
                issueKey = fromPayload;
            }
            if ((issueKey == null || issueKey.isEmpty()) && fromSubject != null && !fromSubject.isEmpty()) {
                issueKey = fromSubject;
            }
        }
        if (issueKey == null || issueKey.isEmpty()) {
            log.warn("DocuSign webhook: could not resolve issueKey (query={}, customField={}, subject={})", issueKeyParam, evt.issueKey, evt.subject);
            return Response.ok("{\"ok\":true,\"ignored\":true}").build();
        }

        Issue issue = issueManager.getIssueObject(issueKey);
        if (issue == null) {
            log.warn("DocuSign webhook: issue not found for key {}", issueKey);
            return Response.ok("{\"ok\":true,\"ignored\":true}").build();
        }

        if (evt.envelopeId == null || evt.envelopeId.trim().isEmpty()) {
            log.warn("DocuSign webhook: missing envelopeId for issue {}", issueKey);
            return Response.ok("{\"ok\":true,\"ignored\":true}").build();
        }

        String envelopeId = sanitizeEnvelopeId(evt.envelopeId);
        if (!trusted) {
            // If the webhook isn't authenticated, only accept it when the envelope is already known for this issue.
            // This reduces spoofing risk when issueKey isn't in the URL.
            try {
                if (!DocusignAoStore.hasEnvelope(issueKey, envelopeId)) {
                    return Response.ok("{\"ok\":true,\"ignored\":true,\"unknownEnvelope\":true}").build();
                }
            } catch (Exception ignore) {
            }
        }
        String payloadHash = null;
        try {
            payloadHash = sha256Hex(payload);
        } catch (Exception ignore) {
            payloadHash = null;
        }
        String statusLower = sanitize(evt.envelopeStatus != null ? evt.envelopeStatus : "");
        statusLower = statusLower != null ? statusLower.toLowerCase() : "";

        List<RecipientStatus> statuses = new ArrayList<>();
        for (ConnectRecipient r : evt.recipients) {
            if (r == null) continue;
            statuses.add(new RecipientStatus(r.name, r.email, r.status, r.routingOrder));
        }
        String payloadText = null;
        try {
            payloadText = new String(payload, StandardCharsets.UTF_8);
            if (payloadText.length() > 8000) {
                payloadText = payloadText.substring(0, 8000);
            }
        } catch (Exception ignore) {
            payloadText = null;
        }

        // Atomic idempotency: record this payload hash in AO before writing issue properties.
        try {
            boolean newlyRecorded = DocusignAoStore.recordConnectWebhookIfNew(issueKey, envelopeId, statusLower, statuses, payloadText, payloadHash);
            if (!newlyRecorded) {
                return Response.ok("{\"ok\":true,\"ignored\":true,\"duplicate\":true}").build();
            }
        } catch (Exception ignore) {
        }

        ApplicationUser actor;
        try {
            actor = resolveWebhookActor();
        } catch (Exception e) {
            log.error("DocuSign webhook: failed to resolve webhook actor user: {}", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson("Webhook actor not configured"))
                    .build();
        }

        EntityPropertyOptions options = EntityPropertyOptions.defaults();
        if (trusted) {
            options = new EntityPropertyOptions.Builder().skipPermissionChecks().build();
        }

        JiraThreadLocalUtil threadLocalUtil = null;
        ApplicationUser previous = null;
        try {
            // Ensure Jira thread-locals are available (some entity property code relies on request/thread context).
            try {
                threadLocalUtil = ComponentAccessor.getComponent(JiraThreadLocalUtil.class);
                if (threadLocalUtil != null) {
                    threadLocalUtil.preCall();
                }
            } catch (Exception ignore) {
                threadLocalUtil = null;
            }

            // Some Jira services still consult the thread-local auth context even when a user is passed in.
            // Ensure the webhook actor is visible as the "logged in user" for this request thread.
            if (authContext != null) {
                previous = authContext.getLoggedInUser();
                authContext.setLoggedInUser(actor);
            }

            setIssuePropertyJson(actor, issue, "docusign.envelopeId", wrapValueJson(envelopeId), options);
            setIssuePropertyJson(actor, issue, "docusign.envelopeStatus", wrapValueJson(statusLower), options);
            try {
                upsertEnvelopeHistory(actor, issue, envelopeId, statusLower, options);
            } catch (Exception e) {
                log.debug("DocuSign webhook: failed to update envelope history for {}: {}", issueKey, e.getMessage());
            }

            // Persist raw recipient statuses (useful for debugging)
            JsonArray signersRaw = new JsonArray();
            for (ConnectRecipient r : evt.recipients) {
                JsonObject obj = new JsonObject();
                obj.addProperty("email", r.email);
                obj.addProperty("name", r.name);
                obj.addProperty("status", r.status);
                obj.addProperty("routingOrder", r.routingOrder);
                signersRaw.add(obj);
            }
            try {
                setIssuePropertyJson(actor, issue, "docusign.signers", signersRaw.toString(), options);
            } catch (Exception e) {
                log.debug("DocuSign webhook: failed to persist docusign.signers for {}: {}", issueKey, e.getMessage());
            }

            // Persist UI-friendly recipient state (CURRENT/PENDING/COMPLETED) used by the panel.
            JsonArray signerUi = buildUiSignerState(evt.recipients);
            try {
                setIssuePropertyJson(actor, issue, "docusign.signerUiState", signerUi.toString(), options);
            } catch (Exception e) {
                log.debug("DocuSign webhook: failed to persist docusign.signerUiState for {}: {}", issueKey, e.getMessage());
            }

            // AO persistence already handled above for idempotency.
        } catch (Exception e) {
            log.error("DocuSign webhook: failed to persist state for issue {}: {}", issueKey, e.getMessage(), e);
            // Always return 2xx so DocuSign doesn't retry forever on a permanent failure.
            return Response.ok("{\"ok\":true,\"persisted\":false}").build();
        } finally {
            if (authContext != null) {
                try {
                    if (previous != null) {
                        authContext.setLoggedInUser(previous);
                    } else {
                        authContext.clearLoggedInUser();
                    }
                } catch (Exception ignore) {
                    // ignore cleanup errors
                }
            }
            if (threadLocalUtil != null) {
                try {
                    threadLocalUtil.postCall();
                } catch (Exception ignore) {
                    // ignore cleanup errors
                }
            }
        }

        return Response.ok("{\"ok\":true}").build();
    }

    private String sha256Hex(byte[] payload) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(payload != null ? payload : new byte[0]);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private byte[] readWebhookPayloadBytes(HttpServletRequest req) throws Exception {
        if (req == null) return new byte[0];
        InputStream in = req.getInputStream();
        String enc = req.getHeader("Content-Encoding");
        if (enc != null && enc.trim().equalsIgnoreCase("gzip")) {
            in = new GZIPInputStream(in);
        }
        byte[] raw = IOUtils.toByteArray(in);
        return stripLeadingXmlJunk(raw);
    }

    private byte[] stripLeadingXmlJunk(byte[] raw) {
        if (raw == null || raw.length == 0) return raw;
        int start = 0;
        // UTF-8 BOM
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        // Skip leading whitespace/control until first '<' (common cause of "Content is not allowed in prolog.")
        int firstLt = -1;
        for (int i = start; i < raw.length; i++) {
            if (raw[i] == (byte) '<') { firstLt = i; break; }
        }
        if (firstLt > start) {
            boolean allIgnorable = true;
            for (int i = start; i < firstLt; i++) {
                byte b = raw[i];
                // allow common whitespace
                if (b == (byte) ' ' || b == (byte) '\t' || b == (byte) '\r' || b == (byte) '\n') continue;
                // other control chars are considered junk
                if ((b & 0xFF) < 0x20) continue;
                allIgnorable = false;
                break;
            }
            if (allIgnorable) {
                start = firstLt;
            }
        }
        if (start <= 0) return raw;
        int len = raw.length - start;
        if (len <= 0) return new byte[0];
        byte[] out = new byte[len];
        System.arraycopy(raw, start, out, 0, len);
        return out;
    }

    @GET
    @Path("/status/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshStatus(@QueryParam("issueKey") String issueKey, @QueryParam("envelopeId") String envelopeIdParam) {
        try {
            if (issueKey == null || issueKey.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("issueKey is required"))
                        .build();
            }
            Issue issue = issueManager.getIssueObject(issueKey);
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Invalid issue key: " + issueKey))
                        .build();
            }
            String envelopeId = sanitizeEnvelopeId(envelopeIdParam);
            if (envelopeId == null || envelopeId.trim().isEmpty()) {
                envelopeId = sanitizeEnvelopeId(readIssueProperty(issue, "docusign.envelopeId"));
            }
            if (envelopeId == null || envelopeId.trim().isEmpty()) {
                // Fall back to AO (source of truth) if issue properties weren't persisted.
                try {
                    JsonObject aoState = DocusignAoStore.loadActiveIssueState(issue.getKey());
                    if (aoState != null && aoState.has("envelopeId") && !aoState.get("envelopeId").isJsonNull()) {
                        envelopeId = sanitizeEnvelopeId(aoState.get("envelopeId").getAsString());
                    }
                } catch (Exception ignore) {
                }
            }
            if (envelopeId == null || envelopeId.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("No envelopeId found on issue"))
                        .build();
            }

            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            String accessToken = resolveAccessTokenForUser(user);
            if (accessToken == null || accessToken.trim().isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("DocuSign is not connected or token expired. Please click 'Connect DocuSign' and try again."))
                        .build();
            }

            try {
                String resolvedRestBase = resolveRestBaseForUser(user);
                String resolvedAccountId = requireAccountIdForUser(user);
                String envelopeStatus = fetchEnvelopeStatus(envelopeId, accessToken, resolvedRestBase, resolvedAccountId);
                List<RecipientStatus> statuses = new DocusignRecipientStatusService(resolvedRestBase, resolvedAccountId).getRecipientStatuses(envelopeId, accessToken);
                storeRecipientStatus(issue, envelopeId, accessToken, resolvedRestBase, resolvedAccountId, statuses);
                storeEnvelopeMeta(issue, envelopeId, envelopeStatus);
                try {
                    DocusignAoStore.recordStatusUpdate(issue.getKey(), envelopeId, envelopeStatus, statuses, "status.refresh", null);
                } catch (Exception ignore) {
                }

                boolean attached = false;
                String signedName = null;
                Long signedId = null;
                if ("completed".equalsIgnoreCase(envelopeStatus)) {
                    String signedAttached = readIssueProperty(issue, "docusign.signedAttached");
                    if (signedAttached == null || !Boolean.parseBoolean(signedAttached)) {
                        byte[] pdf = new DocusignDocumentFetchService(resolvedRestBase, resolvedAccountId).fetchSignedPdf(envelopeId, accessToken);
                        documentDownloadService.attachSignedPdfIfMissing(issue, pdf, issue.getKey() + ".pdf");
                        Attachment signed = findSignedAttachment(issue);
                        if (signed != null) {
                            signedName = signed.getFilename();
                            signedId = signed.getId();
                        }
                        ApplicationUser userCtx = resolveUser();
                        setIssuePropertyJson(userCtx, issue, "docusign.signedAttached", wrapValueJson(Boolean.TRUE));
                        attached = true;
                    } else {
                        Attachment signed = findSignedAttachment(issue);
                        if (signed != null) {
                            signedName = signed.getFilename();
                            signedId = signed.getId();
                        }
                    }
                }

                statuses.sort((a, b) -> {
                    int ra = safeParseInt(a.getRoutingOrder(), Integer.MAX_VALUE);
                    int rb = safeParseInt(b.getRoutingOrder(), Integer.MAX_VALUE);
                    return Integer.compare(ra, rb);
                });
                JsonArray signerArr = new JsonArray();
                boolean activeSet = false;
                for (RecipientStatus s : statuses) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("email", s.getEmail());
                    obj.addProperty("name", s.getName());
                    obj.addProperty("routingOrder", s.getRoutingOrder());
                    String ui;
                    String raw = s.getStatus() != null ? s.getStatus().toLowerCase() : "";
                    if ("completed".equals(raw) || "signed".equals(raw)) {
                        ui = "COMPLETED";
                    } else if (!activeSet) {
                        ui = "CURRENT";
                        activeSet = true;
                    } else {
                        ui = "PENDING";
                    }
                    obj.addProperty("uiStatus", ui);
                    signerArr.add(obj);
                }

                ApplicationUser userCtx = resolveUser();
                try {
                    setIssuePropertyJson(userCtx, issue, "docusign.signerUiState", signerArr.toString());
                } catch (Exception e) {
                    log.warn("Failed to serialize signer UI state during refresh for {}: {}", issue.getKey(), e.getMessage());
                }
                storeEnvelopeMeta(issue, envelopeId, envelopeStatus);

                JsonObject resp = new JsonObject();
                resp.addProperty("envelopeStatus", envelopeStatus);
                resp.addProperty("signedAttached", attached);
                try {
                    resp.add("signedAttachments", collectSignedAttachments(issue));
                } catch (Exception ignore) {
                }
                if (signedName != null) {
                    resp.addProperty("signedAttachmentName", signedName);
                }
                if (signedId != null) {
                    resp.addProperty("signedAttachmentId", signedId);
                }
                if ("completed".equalsIgnoreCase(envelopeStatus)) {
                    String downloadUrl = buildSignedDownloadUrl(envelopeId, issue.getKey());
                    if (downloadUrl != null) {
                        resp.addProperty("signedDownloadUrl", downloadUrl);
                    }
                    if (signedName == null) {
                        resp.addProperty("signedAttachmentName", defaultSignedFileName(issue, envelopeId));
                    }
                }
                resp.add("signers", signerArr);
                resp.addProperty("envelopeId", envelopeId);

                return Response.ok(resp.toString()).build();
            } catch (Exception apiEx) {
                String msg = apiEx.getMessage() != null ? apiEx.getMessage() : "Failed to refresh status";
                log.error("DocuSign refresh failed for {}: {}", issueKey, msg, apiEx);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(errorJson(msg))
                        .build();
            }

        } catch (Exception e) {
            log.error("Failed to refresh status", e);
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to refresh status";
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson(msg))
                    .build();
        }
    }

    @GET
    @Path("/status/live")
    @Produces(MediaType.APPLICATION_JSON)
    public Response liveStatus(@QueryParam("envelopeId") String envelopeId, @QueryParam("issueKey") String issueKey) {
        try {
            String resolvedEnvelopeId = sanitizeEnvelopeId(envelopeId);
            Issue issue = null;
            if ((resolvedEnvelopeId == null || resolvedEnvelopeId.isEmpty()) && issueKey != null && !issueKey.trim().isEmpty()) {
                issue = issueManager.getIssueObject(issueKey);
                if (issue == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(errorJson("Invalid issue key: " + issueKey))
                            .build();
                }
                String stored = sanitizeEnvelopeId(readIssueProperty(issue, "docusign.envelopeId"));
                if (stored == null || stored.trim().isEmpty()) {
                    try {
                        JsonObject aoState = DocusignAoStore.loadActiveIssueState(issue.getKey());
                        if (aoState != null && aoState.has("envelopeId") && !aoState.get("envelopeId").isJsonNull()) {
                            stored = sanitizeEnvelopeId(aoState.get("envelopeId").getAsString());
                        }
                    } catch (Exception ignore) {
                    }
                }
                resolvedEnvelopeId = stored;
            }
            if (resolvedEnvelopeId == null || resolvedEnvelopeId.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("envelopeId is required"))
                        .build();
            }

            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            String accessToken = resolveAccessTokenForUser(user);
            if (accessToken == null || accessToken.trim().isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("DocuSign is not connected or token expired. Please click 'Connect DocuSign' and try again."))
                        .build();
            }

            String resolvedRestBase = resolveRestBaseForUser(user);
            String resolvedAccountId = requireAccountIdForUser(user);
            String envelopeStatus = fetchEnvelopeStatus(resolvedEnvelopeId, accessToken, resolvedRestBase, resolvedAccountId);
            List<RecipientStatus> statuses = new DocusignRecipientStatusService(resolvedRestBase, resolvedAccountId).getRecipientStatuses(resolvedEnvelopeId, accessToken);

            String signedName = null;
            Long signedId = null;
            if (issue != null) {
                Attachment signed = findSignedAttachment(issue);
                if (signed != null) {
                    signedName = signed.getFilename();
                    signedId = signed.getId();
                }
            }

            statuses.sort((a, b) -> {
                int ra = safeParseInt(a.getRoutingOrder(), Integer.MAX_VALUE);
                int rb = safeParseInt(b.getRoutingOrder(), Integer.MAX_VALUE);
                return Integer.compare(ra, rb);
            });
            JsonArray signerArr = new JsonArray();
            boolean activeSet = false;
            for (RecipientStatus s : statuses) {
                JsonObject obj = new JsonObject();
                obj.addProperty("email", s.getEmail());
                obj.addProperty("name", s.getName());
                obj.addProperty("routingOrder", s.getRoutingOrder());
                String ui;
                String raw = s.getStatus() != null ? s.getStatus().toLowerCase() : "";
                if ("completed".equals(raw) || "signed".equals(raw)) {
                    ui = "COMPLETED";
                } else if (!activeSet) {
                    ui = "CURRENT";
                    activeSet = true;
                } else {
                    ui = "PENDING";
                }
                obj.addProperty("uiStatus", ui);
                signerArr.add(obj);
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("envelopeStatus", envelopeStatus);
            resp.add("signers", signerArr);
            resp.addProperty("envelopeId", resolvedEnvelopeId);
            try {
                resp.add("signedAttachments", collectSignedAttachments(issue));
            } catch (Exception ignore) {
            }
            if (signedName != null) {
                resp.addProperty("signedAttachmentName", signedName);
            }
            if (signedId != null) {
                resp.addProperty("signedAttachmentId", signedId);
            }
            if ("completed".equalsIgnoreCase(envelopeStatus)) {
                String downloadUrl = buildSignedDownloadUrl(resolvedEnvelopeId, issueKey != null ? issueKey : (issue != null ? issue.getKey() : null));
                if (downloadUrl != null) {
                    resp.addProperty("signedDownloadUrl", downloadUrl);
                }
                if (signedName == null) {
                    resp.addProperty("signedAttachmentName", defaultSignedFileName(issue, resolvedEnvelopeId));
                }
            }

            return Response.ok(resp.toString()).build();
        } catch (Exception e) {
            String msg = friendlyDocuSignError(e, "Failed to fetch live status");
            log.error("DocuSign live status failed: {}", msg, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson(msg))
                    .build();
        }
    }

    @POST
    @Path("/status/attach")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response attachSigned(AttachRequest req) {
        try {
            if (req == null || req.getIssueKey() == null || req.getIssueKey().trim().isEmpty()
                    || req.getEnvelopeId() == null || req.getEnvelopeId().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("issueKey and envelopeId are required"))
                        .build();
            }
            Issue issue = issueManager.getIssueObject(req.getIssueKey());
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("Invalid issue key: " + req.getIssueKey()))
                        .build();
            }

            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            String accessToken = resolveAccessTokenForUser(user);
            if (accessToken == null || accessToken.trim().isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(errorJson("DocuSign is not connected or token expired. Please click 'Connect DocuSign' and try again."))
                        .build();
            }

            String envelopeId = sanitizeEnvelopeId(req.getEnvelopeId());
            String resolvedRestBase = resolveRestBaseForUser(user);
            String resolvedAccountId = requireAccountIdForUser(user);
            String envelopeStatus = fetchEnvelopeStatus(envelopeId, accessToken, resolvedRestBase, resolvedAccountId);
            List<RecipientStatus> statuses = new DocusignRecipientStatusService(resolvedRestBase, resolvedAccountId).getRecipientStatuses(envelopeId, accessToken);

            String signedName = null;
            Long signedId = null;
            boolean attached = false;
            if ("completed".equalsIgnoreCase(envelopeStatus)) {
                try {
                    String mode = req.getMode();
                    boolean combined = mode != null && "combined".equalsIgnoreCase(mode.trim());

                    DocusignDocumentFetchService fetch = new DocusignDocumentFetchService(resolvedRestBase, resolvedAccountId);
                    JsonArray attachedArr = new JsonArray();

                    if (combined) {
                        byte[] pdf = fetch.fetchSignedPdf(envelopeId, accessToken);
                        String fn = defaultSignedFileName(issue, envelopeId);
                        boolean ok = documentDownloadService.attachPdfIfMissing(issue, pdf, fn);
                        Attachment a = ok ? findAttachmentByFilename(issue, fn) : null;
                        if (a != null) {
                            attached = true;
                            JsonObject obj = new JsonObject();
                            obj.addProperty("id", a.getId());
                            obj.addProperty("name", a.getFilename());
                            attachedArr.add(obj);
                        }
                    } else {
                        // Default: attach one PDF per envelope document (not combined).
                        List<DocusignAoStore.DocumentMeta> docs = null;
                        try {
                            docs = DocusignAoStore.loadEnvelopeDocuments(issue.getKey(), envelopeId);
                        } catch (Exception ignore) {
                            docs = null;
                        }
                        if (docs == null || docs.isEmpty()) {
                            docs = new ArrayList<>();
                            try {
                                for (DocusignDocumentFetchService.EnvelopeDocument d : fetch.listEnvelopeDocuments(envelopeId, accessToken)) {
                                    if (d == null) continue;
                                    docs.add(new DocusignAoStore.DocumentMeta(d.documentId, d.name));
                                }
                            } catch (Exception ignore) {
                                docs = new ArrayList<>();
                            }
                        }

                        List<DocusignAoStore.DocumentMeta> contentDocs = new ArrayList<>();
                        for (DocusignAoStore.DocumentMeta d : docs) {
                            if (d == null || d.documentId == null) continue;
                            String id = d.documentId.trim();
                            if (id.isEmpty()) continue;
                            String lid = id.toLowerCase(Locale.ROOT);
                            if ("combined".equals(lid) || "certificate".equals(lid) || "summary".equals(lid)) continue;
                            contentDocs.add(d);
                        }

                        if (contentDocs.isEmpty()) {
                            // Fallback: try numeric documentIds (1..10). If this yields at least one doc, keep them.
                            int maxTry = 10;
                            for (int i = 1; i <= maxTry; i++) {
                                String docId = String.valueOf(i);
                                try {
                                    byte[] pdf = fetch.fetchDocumentPdf(envelopeId, docId, accessToken);
                                    if (!looksLikePdf(pdf)) {
                                        continue;
                                    }
                                    String fn = signedFileNameForDoc(issue, envelopeId, docId, "document-" + docId);
                                    boolean ok = documentDownloadService.attachPdfIfMissing(issue, pdf, fn);
                                    Attachment a = ok ? findAttachmentByFilename(issue, fn) : null;
                                    if (a != null) {
                                        attached = true;
                                        JsonObject obj = new JsonObject();
                                        obj.addProperty("id", a.getId());
                                        obj.addProperty("name", a.getFilename());
                                        attachedArr.add(obj);
                                    }
                                } catch (Exception ignore) {
                                    // stop once numeric ids stop working
                                    break;
                                }
                            }

                            if (attachedArr.size() == 0) {
                                // Last resort: attach the combined doc if DocuSign doesn't expose documentIds.
                                byte[] pdf = fetch.fetchSignedPdf(envelopeId, accessToken);
                                String fn = defaultSignedFileName(issue, envelopeId);
                                boolean ok = documentDownloadService.attachPdfIfMissing(issue, pdf, fn);
                                Attachment a = ok ? findAttachmentByFilename(issue, fn) : null;
                                if (a != null) {
                                    attached = true;
                                    JsonObject obj = new JsonObject();
                                    obj.addProperty("id", a.getId());
                                    obj.addProperty("name", a.getFilename());
                                    attachedArr.add(obj);
                                }
                            }
                        } else {
                            for (DocusignAoStore.DocumentMeta d : contentDocs) {
                                byte[] pdf = fetch.fetchDocumentPdf(envelopeId, d.documentId, accessToken);
                                String fn = signedFileNameForDoc(issue, envelopeId, d.documentId, d.filename);
                                boolean ok = documentDownloadService.attachPdfIfMissing(issue, pdf, fn);
                                Attachment a = ok ? findAttachmentByFilename(issue, fn) : null;
                                if (a != null) {
                                    attached = true;
                                    JsonObject obj = new JsonObject();
                                    obj.addProperty("id", a.getId());
                                    obj.addProperty("name", a.getFilename());
                                    attachedArr.add(obj);
                                }
                            }
                        }
                    }

                    if (attachedArr.size() > 0) {
                        try {
                            ApplicationUser userCtx = resolveUser();
                            setIssuePropertyJson(userCtx, issue, "docusign.signedAttachments", attachedArr.toString());
                            setIssuePropertyJson(userCtx, issue, "docusign.signedAttached", wrapValueJson(Boolean.TRUE));
                        } catch (Exception ignore) {
                        }
                    }

                    // Compatibility: surface the first signed attachment.
                    if (attachedArr.size() > 0) {
                        JsonObject first = attachedArr.get(0).getAsJsonObject();
                        if (first.has("name")) signedName = first.get("name").getAsString();
                        if (first.has("id")) signedId = first.get("id").getAsLong();
                    } else {
                        Attachment signed = findSignedAttachment(issue);
                        if (signed != null) {
                            signedName = signed.getFilename();
                            signedId = signed.getId();
                        }
                    }
                } catch (Exception attachEx) {
                    log.warn("Failed to attach signed PDF for {} envelope {}: {}", issue.getKey(), envelopeId, attachEx.getMessage());
                }
            }

            statuses.sort((a, b) -> {
                int ra = safeParseInt(a.getRoutingOrder(), Integer.MAX_VALUE);
                int rb = safeParseInt(b.getRoutingOrder(), Integer.MAX_VALUE);
                return Integer.compare(ra, rb);
            });
            JsonArray signerArr = new JsonArray();
            boolean activeSet = false;
            for (RecipientStatus s : statuses) {
                JsonObject obj = new JsonObject();
                obj.addProperty("email", s.getEmail());
                obj.addProperty("name", s.getName());
                obj.addProperty("routingOrder", s.getRoutingOrder());
                String ui;
                String raw = s.getStatus() != null ? s.getStatus().toLowerCase() : "";
                if ("completed".equals(raw) || "signed".equals(raw)) {
                    ui = "COMPLETED";
                } else if (!activeSet) {
                    ui = "CURRENT";
                    activeSet = true;
                } else {
                    ui = "PENDING";
                }
                obj.addProperty("uiStatus", ui);
                signerArr.add(obj);
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("envelopeStatus", envelopeStatus);
            resp.add("signers", signerArr);
            resp.addProperty("envelopeId", envelopeId);
            resp.addProperty("signedAttached", attached);
            try {
                resp.add("signedAttachments", collectSignedAttachments(issue));
            } catch (Exception ignore) {
            }
            if (signedName != null) {
                resp.addProperty("signedAttachmentName", signedName);
            }
            if (signedId != null) {
                resp.addProperty("signedAttachmentId", signedId);
            }
            if ("completed".equalsIgnoreCase(envelopeStatus)) {
                String downloadUrl = buildSignedDownloadUrl(envelopeId, issue.getKey());
                if (downloadUrl != null) {
                    resp.addProperty("signedDownloadUrl", downloadUrl);
                }
                if (signedName == null) {
                    resp.addProperty("signedAttachmentName", defaultSignedFileName(issue, envelopeId));
                }
            }
            return Response.ok(resp.toString()).build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to attach signed document";
            log.error("attachSigned failed: {}", msg, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorJson(msg))
                    .build();
        }
    }

    @GET
    @Path("/signed/download")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON})
    public Response downloadSigned(@QueryParam("envelopeId") String envelopeId,
                                   @QueryParam("issueKey") String issueKey,
                                   @QueryParam("documentId") String documentId) {
        try {
            String resolvedId = sanitizeEnvelopeId(envelopeId);
            Issue issue = null;
            if ((resolvedId == null || resolvedId.isEmpty()) && issueKey != null && !issueKey.trim().isEmpty()) {
                issue = issueManager.getIssueObject(issueKey);
                if (issue == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(errorJson("Invalid issue key: " + issueKey))
                            .build();
                }
                resolvedId = sanitizeEnvelopeId(readIssueProperty(issue, "docusign.envelopeId"));
            } else if (issueKey != null && !issueKey.trim().isEmpty()) {
                issue = issueManager.getIssueObject(issueKey);
            }

            if (resolvedId == null || resolvedId.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(errorJson("envelopeId is required"))
                        .build();
            }

            ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
            String accessToken = resolveAccessTokenForUser(user);
            if (accessToken == null || accessToken.trim().isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(errorJson("DocuSign is not connected or token expired. Please click 'Connect DocuSign' and try again."))
                        .build();
            }

            String resolvedRestBase = resolveRestBaseForUser(user);
            String resolvedAccountId = requireAccountIdForUser(user);
            String docId = (documentId != null && !documentId.trim().isEmpty()) ? documentId.trim() : "combined";
            DocusignDocumentFetchService fetch = new DocusignDocumentFetchService(resolvedRestBase, resolvedAccountId);
            byte[] pdf = "combined".equalsIgnoreCase(docId) ? fetch.fetchSignedPdf(resolvedId, accessToken) : fetch.fetchDocumentPdf(resolvedId, docId, accessToken);
            String filename = "combined".equalsIgnoreCase(docId) ? defaultSignedFileName(issue, resolvedId) : signedFileNameForDoc(issue, resolvedId, docId, "document-" + docId);
            return Response.ok(pdf)
                    .type("application/pdf")
                    .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                    .build();
        } catch (Exception e) {
            String msg = friendlyDocuSignError(e, "Failed to download signed document");
            log.error("downloadSigned failed for envelope {}: {}", envelopeId, msg, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(errorJson(msg))
                    .build();
        }
    }

    public static class AttachRequest {
        private String issueKey;
        private String envelopeId;
        private String mode; // "individual" (default) | "combined"

        public String getIssueKey() { return issueKey; }
        public void setIssueKey(String issueKey) { this.issueKey = issueKey; }
        public String getEnvelopeId() { return envelopeId; }
        public void setEnvelopeId(String envelopeId) { this.envelopeId = envelopeId; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    private List<DocusignSigner> convertMixedSigners(List<SignerInput> inputs, Map<Long, String> attachmentIdToDocumentId) {
        List<DocusignSigner> signers = new ArrayList<>();
        if (inputs == null) {
            return signers;
        }

        for (int i = 0; i < inputs.size(); i++) {
            SignerInput input = inputs.get(i);
            if (input == null) {
                throw new IllegalArgumentException("Signer at index " + i + " is null");
            }
            String type = input.getType();
            String value = input.getValue();
            if (type == null || value == null || type.trim().isEmpty() || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Signer type/value missing at index " + i);
            }

            String order = String.valueOf(i + 1);
            String recipientId = order;
            String pageNumber = sanitize(input.getPageNumber());
            String xPos = sanitize(input.getxPosition());
            String yPos = sanitize(input.getyPosition());
            // Basic numeric validation (server-side). PDF page count validation happens client-side via PDF.js.
            validateOptionalInt(pageNumber, 1, 10000, "pageNumber for signer " + (i + 1));
            validateOptionalInt(xPos, 0, 100000, "xPosition for signer " + (i + 1));
            validateOptionalInt(yPos, 0, 100000, "yPosition for signer " + (i + 1));
            List<DocusignService.TabPosition> tabPositions = new ArrayList<>();
            if (input.getPositions() != null) {
                if (input.getPositions().size() > 50) {
                    throw new IllegalArgumentException("Too many signature positions for signer " + (i + 1) + " (max 50).");
                }
                for (SignerPosition sp : input.getPositions()) {
                    if (sp == null) continue;
                    String pp = sanitize(sp.getPageNumber());
                    String xp = sanitize(sp.getxPosition());
                    String yp = sanitize(sp.getyPosition());
                    String documentId = null;
                    try {
                        Long attId = sp.getAttachmentId();
                        if (attId != null && attachmentIdToDocumentId != null) {
                            documentId = attachmentIdToDocumentId.get(attId);
                        }
                    } catch (Exception ignore) {
                    }
                    boolean any = (pp != null && !pp.isEmpty()) || (xp != null && !xp.isEmpty()) || (yp != null && !yp.isEmpty());
                    if (any) {
                        if (pp == null || pp.isEmpty() || xp == null || xp.isEmpty() || yp == null || yp.isEmpty()) {
                            throw new IllegalArgumentException("Each signature position must include pageNumber, xPosition, and yPosition (signer " + (i + 1) + ").");
                        }
                        validateOptionalInt(pp, 1, 10000, "pageNumber for signer " + (i + 1));
                        validateOptionalInt(xp, 0, 100000, "xPosition for signer " + (i + 1));
                        validateOptionalInt(yp, 0, 100000, "yPosition for signer " + (i + 1));
                        tabPositions.add(new DocusignService.TabPosition(pp, xp, yp, documentId));
                    }
                }
            }

            if ("JIRA_USER".equalsIgnoreCase(type)) {
                ApplicationUser user = userManager.getUserByKey(value.trim());
                if (user == null) {
                    throw new IllegalArgumentException("User not found for key: " + value);
                }
                String email = sanitize(user.getEmailAddress());
                if (email == null || email.trim().isEmpty()) {
                    throw new IllegalArgumentException("User " + user.getDisplayName() + " has no email address.");
                }
                String name = sanitize(user.getDisplayName() != null ? user.getDisplayName().trim() : user.getName());
                signers.add(new DocusignSigner(name, email.trim(), recipientId, order, pageNumber, xPos, yPos, tabPositions.isEmpty() ? null : tabPositions));
            } else if ("EXTERNAL".equalsIgnoreCase(type)) {
                String email = sanitize(value);
                if (!email.contains("@")) {
                    throw new IllegalArgumentException("External signer email is invalid at index " + i);
                }
                signers.add(new DocusignSigner(email, email, recipientId, order, pageNumber, xPos, yPos, tabPositions.isEmpty() ? null : tabPositions));
            } else {
                throw new IllegalArgumentException("Unsupported signer type at index " + i + ": " + type);
            }
        }
        return signers;
    }

    private void validateOptionalInt(String value, int min, int max, String field) {
        if (value == null || value.trim().isEmpty()) return;
        try {
            int v = Integer.parseInt(value.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    public static class SendRequest {
        private String issueKey;
        private List<Long> attachmentIds;
        private List<SignerInput> signers;

        public SendRequest() {
        }

        public String getIssueKey() { return issueKey; }
        public void setIssueKey(String issueKey) { this.issueKey = issueKey; }
        public List<Long> getAttachmentIds() { return attachmentIds; }
        public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
        public List<SignerInput> getSigners() { return signers; }
        public void setSigners(List<SignerInput> signers) { this.signers = signers; }
    }

    public static class SignerInput {
        private String type;
        private String value;
        private String pageNumber;
        private String xPosition;
        private String yPosition;
        private List<SignerPosition> positions;

        public SignerInput() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getPageNumber() { return pageNumber; }
        public void setPageNumber(String pageNumber) { this.pageNumber = pageNumber; }
        public String getxPosition() { return xPosition; }
        public void setxPosition(String xPosition) { this.xPosition = xPosition; }
        public String getyPosition() { return yPosition; }
        public void setyPosition(String yPosition) { this.yPosition = yPosition; }
        public List<SignerPosition> getPositions() { return positions; }
        public void setPositions(List<SignerPosition> positions) { this.positions = positions; }
    }

    public static class SignerPosition {
        private Long attachmentId;
        private String filename;
        private String pageNumber;
        private String xPosition;
        private String yPosition;

        public Long getAttachmentId() { return attachmentId; }
        public void setAttachmentId(Long attachmentId) { this.attachmentId = attachmentId; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getPageNumber() { return pageNumber; }
        public void setPageNumber(String pageNumber) { this.pageNumber = pageNumber; }
        public String getxPosition() { return xPosition; }
        public void setxPosition(String xPosition) { this.xPosition = xPosition; }
        public String getyPosition() { return yPosition; }
        public void setyPosition(String yPosition) { this.yPosition = yPosition; }
    }

    private String fetchEnvelopeStatus(String envelopeId, String accessToken, String restBase, String accountId) throws Exception {
        String rb = (restBase != null && !restBase.trim().isEmpty()) ? restBase.trim() : this.restBase;
        String aid = (accountId != null && !accountId.trim().isEmpty()) ? accountId.trim() : this.accountId;
        String url = rb + "/v2.1/accounts/" + aid + "/envelopes/" + envelopeId;
        try (CloseableHttpClient client = DocusignHttpClientFactory.create()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Bearer " + accessToken);
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

    private static String readSecretCfg(String key, boolean required, String def) {
        if (required) {
            return DocusignConfig.getRequiredSecretString(key);
        }
        return DocusignConfig.getSecretString(key, def);
    }

    private String headerTrim(String name) {
        if (httpRequest == null || name == null) return null;
        String v = httpRequest.getHeader(name);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String computeHmacBase64(byte[] payload, String secret, String algorithm) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        byte[] digest = mac.doFinal(payload != null ? payload : new byte[0]);
        return Base64.getEncoder().encodeToString(digest);
    }

    private ApplicationUser resolveWebhookActor() {
        String configured = readCfg("DOCUSIGN_WEBHOOK_ACTOR", false, "admin");
        ApplicationUser actor = null;
        if (configured != null && !configured.trim().isEmpty()) {
            String v = configured.trim();
            actor = userManager.getUserByKey(v);
            if (actor == null) {
                actor = userManager.getUserByName(v);
            }
        }
        if (actor == null) {
            actor = userManager.getUserByName("admin");
        }
        if (actor == null) {
            throw new IllegalStateException("Webhook actor user not found (set DOCUSIGN_WEBHOOK_ACTOR)");
        }
        return actor;
    }

    private static class ConnectRecipient {
        String email;
        String name;
        String status;
        String routingOrder;
    }

    private static class ConnectWebhookEvent {
        String envelopeId;
        String envelopeStatus;
        String subject;
        String issueKey;
        List<ConnectRecipient> recipients = new ArrayList<>();
    }

    private ConnectWebhookEvent parseConnectWebhook(byte[] payload) throws Exception {
        Document doc = parseXmlSecure(payload);
        XPath xp = XPathFactory.newInstance().newXPath();

        ConnectWebhookEvent evt = new ConnectWebhookEvent();
        evt.envelopeId = evalString(xp, doc, "string(//*[local-name()='EnvelopeStatus']/*[local-name()='EnvelopeID'][1])");
        evt.envelopeStatus = evalString(xp, doc, "string(//*[local-name()='EnvelopeStatus']/*[local-name()='Status'][1])");
        evt.subject = evalString(xp, doc, "string(//*[local-name()='EnvelopeStatus']/*[local-name()='Subject'][1])");
        evt.issueKey = findCustomFieldValue(xp, doc, "jiraIssueKey");

        NodeList recipients = (NodeList) xp.evaluate(
                "//*[local-name()='RecipientStatuses']/*[local-name()='RecipientStatus']",
                doc,
                XPathConstants.NODESET
        );
        for (int i = 0; i < recipients.getLength(); i++) {
            Node n = recipients.item(i);
            if (n == null) continue;
            ConnectRecipient r = new ConnectRecipient();
            r.email = evalString(xp, n, "string(./*[local-name()='Email'][1])");
            r.name = evalString(xp, n, "string(./*[local-name()='UserName'][1])");
            r.status = evalString(xp, n, "string(./*[local-name()='Status'][1])");
            r.routingOrder = evalString(xp, n, "string(./*[local-name()='RoutingOrder'][1])");
            evt.recipients.add(r);
        }
        return evt;
    }

    private Document parseXmlSecure(byte[] payload) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setExpandEntityReferences(false);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignore) {}
        try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignore) {}
        try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignore) {}
        try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}
        try { dbf.setXIncludeAware(false); } catch (Exception ignore) {}

        DocumentBuilder builder = dbf.newDocumentBuilder();
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload != null ? payload : new byte[0])) {
            return builder.parse(in);
        }
    }

    private String evalString(XPath xp, Object docOrNode, String expr) {
        try {
            String s = (String) xp.evaluate(expr, docOrNode, XPathConstants.STRING);
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private String findCustomFieldValue(XPath xp, Document doc, String name) {
        if (xp == null || doc == null || name == null) return null;
        try {
            // Handle both <CustomFields><CustomField>... and <CustomFields><TextCustomField>... variants.
            NodeList fields = (NodeList) xp.evaluate(
                    "//*[local-name()='CustomFields']//*[local-name()='CustomField' or local-name()='TextCustomField']",
                    doc,
                    XPathConstants.NODESET
            );
            for (int i = 0; i < fields.getLength(); i++) {
                Node n = fields.item(i);
                if (n == null) continue;
                String nName = evalString(xp, n, "string(./*[local-name()='Name'][1])");
                if (nName == null) continue;
                if (!name.equals(nName.trim())) continue;
                String val = evalString(xp, n, "string(./*[local-name()='Value'][1])");
                if (val != null && !val.trim().isEmpty()) return val.trim();
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }

    private String extractIssueKey(String subject) {
        if (subject == null) return null;
        Matcher m = ISSUE_KEY_PATTERN.matcher(subject);
        if (m.find()) {
            return m.group(0);
        }
        return null;
    }

    private JsonArray buildUiSignerState(List<ConnectRecipient> recipients) {
        List<ConnectRecipient> list = recipients != null ? new ArrayList<>(recipients) : new ArrayList<>();
        list.sort((a, b) -> {
            int ra = safeParseInt(a != null ? a.routingOrder : null, Integer.MAX_VALUE);
            int rb = safeParseInt(b != null ? b.routingOrder : null, Integer.MAX_VALUE);
            return Integer.compare(ra, rb);
        });

        JsonArray signerArr = new JsonArray();
        boolean activeSet = false;
        for (ConnectRecipient r : list) {
            if (r == null) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("email", r.email != null ? r.email : "");
            obj.addProperty("name", r.name != null ? r.name : "");
            obj.addProperty("routingOrder", r.routingOrder != null ? r.routingOrder : "");
            String raw = r.status != null ? r.status.toLowerCase() : "";
            String ui;
            if ("completed".equals(raw) || "signed".equals(raw)) {
                ui = "COMPLETED";
            } else if (!activeSet) {
                ui = "CURRENT";
                activeSet = true;
            } else {
                ui = "PENDING";
            }
            obj.addProperty("uiStatus", ui);
            signerArr.add(obj);
        }
        return signerArr;
    }

    private int safeParseInt(String val, int def) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return def;
        }
    }

    private ApplicationUser resolveUser() {
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        if (user == null) {
            throw new IllegalStateException("No logged-in user available to store DocuSign properties.");
        }
        return user;
    }

    private String friendlyDocuSignError(Exception e, String defaultMsg) {
        if (e == null) {
            return defaultMsg;
        }
        String msg = e.getMessage() != null ? e.getMessage() : defaultMsg;
        String parsed = tryParseDocuSignHttpError(msg);
        if (parsed != null && !parsed.trim().isEmpty()) {
            msg = parsed;
        }
        String lower = msg.toLowerCase();
        if (lower.contains("401") || lower.contains("invalid_token") || lower.contains("unauthorized")) {
            return "DocuSign token expired or unauthorized. Please reconnect.";
        }
        if (lower.contains("unknownhostexception")
                || lower.contains("nodename nor servname")
                || lower.contains("name or service not known")
                || lower.contains("temporary failure in name resolution")) {
            return "Jira can't resolve the DocuSign host (DNS failure). Ensure the Jira server has internet/DNS access (and any firewall/proxy allows Java), then restart Jira.";
        }
        if (lower.contains("envelope") && lower.contains("not found")) {
            return "DocuSign envelope not found.";
        }
        return msg;
    }

    private String tryParseDocuSignHttpError(String msg) {
        if (msg == null) return null;
        // Common internal error format: "HTTP 400 from DocuSign: {json}"
        int idx = msg.indexOf(" from DocuSign:");
        if (!msg.startsWith("HTTP ") || idx < 0) {
            return null;
        }
        String httpPart = msg.substring(0, idx).trim(); // "HTTP 400"
        String bodyPart = msg.substring(idx + " from DocuSign:".length()).trim();
        int code = -1;
        try {
            String[] parts = httpPart.split("\\s+");
            if (parts.length >= 2) {
                code = Integer.parseInt(parts[1]);
            }
        } catch (Exception ignore) {
        }
        if (bodyPart.isEmpty()) {
            return (code > 0) ? ("DocuSign error (HTTP " + code + ")") : null;
        }
        try {
            JsonObject json = GSON.fromJson(bodyPart, JsonObject.class);
            if (json != null) {
                String errorCode = json.has("errorCode") && !json.get("errorCode").isJsonNull() ? json.get("errorCode").getAsString() : null;
                String message = json.has("message") && !json.get("message").isJsonNull() ? json.get("message").getAsString() : null;
                if (message != null && !message.trim().isEmpty()) {
                    if (errorCode != null && !errorCode.trim().isEmpty()) {
                        return "DocuSign error (" + errorCode + "): " + message;
                    }
                    return "DocuSign error: " + message;
                }
            }
        } catch (Exception ignore) {
        }
        // Not JSON or unknown shape; return a condensed form.
        if (code > 0) {
            return "DocuSign error (HTTP " + code + "): " + bodyPart;
        }
        return null;
    }

    private String sanitizeEnvelopeId(String val) {
        if (val == null) {
            return null;
        }
        String trimmed = val.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String sanitize(String input) {
        if (input == null) return "";
        String cleaned = input.replaceAll("[\\p{Cntrl}]", "");
        cleaned = cleaned.replace("\"", "").replace("\\", "");
        return cleaned.trim();
    }

    private void setIssuePropertyJson(ApplicationUser user, Issue issue, String key, String json) {
        setIssuePropertyJson(user, issue, key, json, EntityPropertyOptions.defaults());
    }

    private void setIssuePropertyJson(ApplicationUser user, Issue issue, String key, String json, EntityPropertyOptions options) {
        try {
            String payload = (json == null) ? "null" : json;
            if (jsonEntityPropertyManager == null) {
                throw new IllegalStateException("JsonEntityPropertyManager not available");
            }
            if (issue == null || issue.getId() == null) {
                throw new IllegalArgumentException("Issue is required");
            }
            jsonEntityPropertyManager.put(ISSUE_PROPERTY_ENTITY_NAME, issue.getId(), key, payload);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store " + key, e);
        }
    }

    private String wrapValueJson(String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty("value", value == null ? "" : value);
        return obj.toString();
    }

    private String wrapValueJson(Boolean value) {
        JsonObject obj = new JsonObject();
        obj.addProperty("value", value != null && value);
        return obj.toString();
    }

    private Attachment findAttachmentByFilename(Issue issue, String filename) {
        try {
            if (issue == null || filename == null || filename.trim().isEmpty()) return null;
            String target = filename.trim();
            AttachmentManager am = ComponentAccessor.getAttachmentManager();
            List<Attachment> atts = am.getAttachments(issue);
            if (atts == null) return null;
            for (Attachment a : atts) {
                if (a == null || a.getFilename() == null) continue;
                if (a.getFilename().equalsIgnoreCase(target)) return a;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private boolean looksLikePdf(byte[] data) {
        try {
            if (data == null || data.length < 5) return false;
            return data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F' && data[4] == '-';
        } catch (Exception e) {
            return false;
        }
    }

    private Attachment findSignedAttachment(Issue issue) {
        try {
            if (issue == null) return null;
            AttachmentManager am = ComponentAccessor.getAttachmentManager();
            List<Attachment> atts = am.getAttachments(issue);
            if (atts == null) return null;
            for (Attachment a : atts) {
                if (a == null || a.getFilename() == null) continue;
                String name = a.getFilename();
                if (name.startsWith("Signed_")) {
                    return a;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to locate signed attachment for issue {}: {}", issue != null ? issue.getKey() : "null", e.getMessage());
        }
        return null;
    }

    private JsonArray collectSignedAttachments(Issue issue) {
        JsonArray arr = new JsonArray();
        try {
            if (issue == null) return arr;
            AttachmentManager am = ComponentAccessor.getAttachmentManager();
            List<Attachment> atts = am.getAttachments(issue);
            if (atts == null) return arr;
            for (Attachment a : atts) {
                if (a == null || a.getFilename() == null) continue;
                String name = a.getFilename();
                if (name == null || !name.startsWith("Signed_")) continue;
                JsonObject obj = new JsonObject();
                obj.addProperty("id", a.getId());
                obj.addProperty("name", name);
                arr.add(obj);
            }
        } catch (Exception ignore) {
        }
        return arr;
    }

    private String signedFileNameForDoc(Issue issue, String envelopeId, String documentId, String docName) {
        String issueKey = issue != null ? issue.getKey() : null;
        String env = envelopeId != null ? envelopeId.trim() : "";
        String envShort = env.length() >= 8 ? env.substring(0, 8) : env;
        String docId = (documentId != null && !documentId.trim().isEmpty()) ? documentId.trim() : "doc";

        String base = (docName != null && !docName.trim().isEmpty()) ? docName.trim() : ("document-" + docId);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = sanitize(base);
        if (base == null || base.isEmpty()) base = "document-" + docId;

        StringBuilder sb = new StringBuilder();
        sb.append("Signed_");
        if (issueKey != null && !issueKey.trim().isEmpty()) {
            sb.append(sanitize(issueKey.trim())).append("_");
        }
        if (envShort != null && !envShort.isEmpty()) {
            sb.append(envShort).append("_");
        }
        sb.append("doc").append(docId).append("_").append(base);
        String name = sb.toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        if (name.length() > 160) {
            name = name.substring(0, 156) + ".pdf";
        }
        return name;
    }

    private String buildSignedDownloadUrl(String envelopeId, String issueKey) {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            return null;
        }
        try {
            String ctx = (httpRequest != null && httpRequest.getContextPath() != null) ? httpRequest.getContextPath() : "";
            StringBuilder sb = new StringBuilder();
            if (ctx != null && !ctx.isEmpty()) {
                sb.append(ctx);
            }
            sb.append("/rest/docusign/1.0/send/signed/download?envelopeId=");
            sb.append(URLEncoder.encode(envelopeId.trim(), StandardCharsets.UTF_8.name()));
            if (issueKey != null && !issueKey.trim().isEmpty()) {
                sb.append("&issueKey=").append(URLEncoder.encode(issueKey.trim(), StandardCharsets.UTF_8.name()));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String defaultSignedFileName(Issue issue, String envelopeId) {
        String base = issue != null ? issue.getKey() : envelopeId;
        base = sanitize(base);
        if (base == null || base.isEmpty()) {
            base = "signed-document";
        }
        String name = "Signed_" + base + "_combined";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        return name;
    }

    private String errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message != null ? message : "Internal server error");
        return GSON.toJson(obj);
    }

    private String successJson(String envelopeId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("envelopeId", envelopeId != null ? envelopeId : "");
        obj.addProperty("status", "sent");
        return GSON.toJson(obj);
    }
}
