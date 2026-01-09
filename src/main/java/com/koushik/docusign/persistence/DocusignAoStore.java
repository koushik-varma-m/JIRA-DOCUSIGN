package com.koushik.docusign.persistence;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.user.ApplicationUser;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koushik.docusign.ao.AoDocusignDocument;
import com.koushik.docusign.ao.AoDocusignEnvelope;
import com.koushik.docusign.ao.AoDocusignEvent;
import com.koushik.docusign.ao.AoDocusignSigner;
import com.koushik.docusign.ao.AoDocusignTab;
import com.koushik.docusign.docusign.DocusignService;
import com.koushik.docusign.service.DocusignRecipientStatusService;
import net.java.ao.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Active Objects-backed persistence for DocuSign envelopes/signers/tabs.
 *
 * This is the source of truth; issue properties are treated as a cache for UI convenience.
 */
public final class DocusignAoStore {

    private static final Gson GSON = new Gson();

    private DocusignAoStore() {}

    public static final class SignerMeta {
        public final String type;
        public final String value;

        public SignerMeta(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class DocumentMeta {
        public final String documentId;
        public final String filename;

        public DocumentMeta(String documentId, String filename) {
            this.documentId = documentId;
            this.filename = filename;
        }
    }

    private static ActiveObjects ao() {
        return DocusignAoProvider.get();
    }

    public static boolean isAvailable() {
        return ao() != null;
    }

    public static void recordSentEnvelope(Issue issue,
                                          String envelopeId,
                                          String status,
                                          ApplicationUser sender,
                                          List<Long> attachmentIds,
                                          List<DocusignService.DocusignDocument> documents,
                                          List<DocusignService.DocusignSigner> signers,
                                          Object sendRequestObj,
                                          List<SignerMeta> signerMeta) {
        ActiveObjects ao = ao();
        if (ao == null || issue == null || envelopeId == null || envelopeId.trim().isEmpty()) {
            return;
        }
        final String issueKey = issue.getKey();
        final Long issueId = issue.getId();
        final String envId = envelopeId.trim();
        final String envStatus = status != null ? status : "";

        final String senderUserKey = sender != null ? safe(sender.getKey()) : null;
        final String senderName = sender != null ? safe(sender.getDisplayName()) : null;
        final String senderEmail = sender != null ? safe(sender.getEmailAddress()) : null;

        String reqJsonTmp = null;
        try {
            reqJsonTmp = sendRequestObj != null ? GSON.toJson(sendRequestObj) : null;
        } catch (Exception ignore) {
            reqJsonTmp = null;
        }
        final String reqJson = reqJsonTmp;

        ao.executeInTransaction(() -> {
            Date now = new Date();

            // Mark any current envelopes inactive.
            for (AoDocusignEnvelope prev : ao.find(AoDocusignEnvelope.class, Query.select().where("ISSUE_KEY = ? AND ACTIVE = ?", issueKey, true))) {
                prev.setActive(false);
                prev.setUpdatedAt(now);
                prev.save();
            }

            AoDocusignEnvelope env = ao.create(AoDocusignEnvelope.class);
            env.setIssueKey(issueKey);
            env.setIssueId(issueId);
            env.setEnvelopeId(envId);
            env.setStatus(envStatus);
            env.setActive(true);
            env.setSenderUserKey(senderUserKey);
            env.setSenderDisplayName(senderName);
            env.setSenderEmail(senderEmail);
            env.setCreatedAt(now);
            env.setUpdatedAt(now);
            env.setSendRequestJson(reqJson);
            try {
                JsonObject resp = new JsonObject();
                resp.addProperty("envelopeId", envId);
                resp.addProperty("status", envStatus);
                env.setSendResponseJson(resp.toString());
            } catch (Exception ignore) {
                env.setSendResponseJson(null);
            }
            env.save();

            // Documents (attachments)
            if (documents != null) {
                for (DocusignService.DocusignDocument d : documents) {
                    if (d == null) continue;
                    Long attId = findAttachmentId(attachmentIds, d);
                    if (attId == null) continue;
                    AoDocusignDocument doc = ao.create(AoDocusignDocument.class);
                    doc.setEnvelope(env);
                    doc.setAttachmentId(attId);
                    doc.setFilename(safe(d.filename));
                    doc.setDocumentId(req(d.documentId, "1"));
                    doc.setCreatedAt(now);
                    doc.save();
                }
            }

            // Signers + tabs
            if (signers != null) {
                for (int i = 0; i < signers.size(); i++) {
                    DocusignService.DocusignSigner s = signers.get(i);
                    if (s == null) continue;

                    AoDocusignSigner signer = ao.create(AoDocusignSigner.class);
                    signer.setEnvelope(env);
                    SignerMeta meta = (signerMeta != null && i < signerMeta.size()) ? signerMeta.get(i) : null;
                    String type = meta != null ? safe(meta.type) : null;
                    String value = meta != null ? safe(meta.value) : null;
                    String normalizedType = (type != null && !type.trim().isEmpty()) ? type.trim().toUpperCase() : "UNKNOWN";
                    signer.setSignerType(normalizedType);
                    if ("JIRA_USER".equalsIgnoreCase(normalizedType) && value != null && !value.trim().isEmpty()) {
                        signer.setUserKey(value.trim());
                    } else {
                        signer.setUserKey(null);
                    }
                    signer.setEmail(req(s.email, "unknown"));
                    signer.setName(safe(s.name));
                    signer.setRoutingOrder(parseIntSafe(s.routingOrder, i + 1));
                    signer.setRecipientId(req(s.recipientId, String.valueOf(i + 1)));
                    signer.setStatus("sent");
                    signer.setCreatedAt(now);
                    signer.setUpdatedAt(now);
                    signer.save();

                    // Derive tabs in the same way we create them for DocuSign:
                    // - If positions specify a documentId, apply to that document.
                    // - Otherwise, treat positions as belonging to the first document.
                    // - For any document without explicit tabs, create one default tab.
                    List<DocusignService.TabPosition> tabList = (s.positions != null && !s.positions.isEmpty()) ? s.positions : null;
                    if (documents != null && !documents.isEmpty()) {
                        int posIndex = 0;
                        for (int di = 0; di < documents.size(); di++) {
                            DocusignService.DocusignDocument d = documents.get(di);
                            if (d == null) continue;
                            String documentId = req(d.documentId, "1");
                            if (documentId == null || documentId.isEmpty()) continue;
                            List<DocusignService.TabPosition> docPositions = null;
                            if (tabList != null) {
                                for (DocusignService.TabPosition tp : tabList) {
                                    if (tp == null) continue;
                                    String tpDoc = tp.documentId != null ? tp.documentId.trim() : "";
                                    boolean matches = (!tpDoc.isEmpty() && tpDoc.equals(documentId));
                                    boolean legacyFirstDoc = (tpDoc.isEmpty() && di == 0);
                                    if (matches || legacyFirstDoc) {
                                        if (docPositions == null) docPositions = new ArrayList<>();
                                        docPositions.add(tp);
                                    }
                                }
                            }

                            if (docPositions != null && !docPositions.isEmpty()) {
                                for (DocusignService.TabPosition tp : docPositions) {
                                    createTab(ao, signer, documentId, tp.pageNumber, tp.xPosition, tp.yPosition, posIndex++, now);
                                }
                            } else {
                                createTab(ao, signer, documentId, s.pageNumber, s.xPosition, s.yPosition, posIndex++, now);
                            }
                        }
                    } else {
                        createTab(ao, signer, "1", s.pageNumber, s.xPosition, s.yPosition, 0, now);
                    }
                }
            }

            AoDocusignEvent event = ao.create(AoDocusignEvent.class);
            event.setEnvelope(env);
            event.setEventType("envelope.sent");
            event.setEnvelopeStatus(envStatus);
            event.setOccurredAt(now);
            event.save();

            return null;
        });
    }

    public static void recordStatusUpdate(String issueKey,
                                          String envelopeId,
                                          String envelopeStatus,
                                          List<DocusignRecipientStatusService.RecipientStatus> recipients,
                                          String eventType,
                                          String payload) {
        recordStatusUpdate(issueKey, envelopeId, envelopeStatus, recipients, eventType, payload, null);
    }

    public static void recordStatusUpdate(String issueKey,
                                          String envelopeId,
                                          String envelopeStatus,
                                          List<DocusignRecipientStatusService.RecipientStatus> recipients,
                                          String eventType,
                                          String payload,
                                          String payloadHash) {
        ActiveObjects ao = ao();
        if (ao == null || issueKey == null || envelopeId == null) return;
        final String key = issueKey.trim();
        final String envId = envelopeId.trim();
        final String status = envelopeStatus != null ? envelopeStatus : "";
        final String type = (eventType != null && !eventType.trim().isEmpty()) ? eventType.trim() : "envelope.status";
        final String payloadStr = payload;
        final String hash = (payloadHash != null && !payloadHash.trim().isEmpty()) ? payloadHash.trim() : null;

        ao.executeInTransaction(() -> {
            Date now = new Date();

            AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class, Query.select().where("ISSUE_KEY = ? AND ENVELOPE_ID = ?", key, envId).limit(1));
            AoDocusignEnvelope env = (envs != null && envs.length > 0) ? envs[0] : null;
            if (env == null) {
                // Best-effort: create a minimal envelope record.
                env = ao.create(AoDocusignEnvelope.class);
                env.setIssueKey(key);
                env.setEnvelopeId(envId);
                env.setActive(true);
                env.setCreatedAt(now);
            }

            env.setStatus(status);
            env.setUpdatedAt(now);
            env.save();

            if (recipients != null) {
                for (DocusignRecipientStatusService.RecipientStatus r : recipients) {
                    if (r == null) continue;
                    String email = safe(r.getEmail());
                    if (email == null || email.isEmpty()) continue;
                    AoDocusignSigner[] ss = ao.find(AoDocusignSigner.class,
                            Query.select().where("ENVELOPE_ID = ? AND EMAIL = ?", env.getID(), email).limit(1));
                    if (ss != null && ss.length > 0) {
                        AoDocusignSigner s = ss[0];
                        s.setStatus(safe(r.getStatus()));
                        s.setUpdatedAt(now);
                        s.save();
                    }
                }
            }

            if (hash != null) {
                try {
                    AoDocusignEvent[] existing = ao.find(AoDocusignEvent.class,
                            Query.select().where("ENVELOPE_ID = ? AND PAYLOAD_HASH = ?", env.getID(), hash).limit(1));
                    if (existing != null && existing.length > 0) {
                        return null;
                    }
                } catch (Exception ignore) {
                }
            }

            AoDocusignEvent event = ao.create(AoDocusignEvent.class);
            event.setEnvelope(env);
            event.setEventType(type);
            event.setEnvelopeStatus(status);
            event.setOccurredAt(now);
            event.setPayloadHash(hash);
            event.setPayload(payloadStr);
            event.save();

            return null;
        });
    }

    /**
     * Returns true if an event for this envelope already has the given payload hash.
     * Intended for webhook idempotency (Connect retries can deliver the same payload multiple times).
     */
    public static boolean hasEventPayloadHash(String issueKey, String envelopeId, String payloadHash) {
        ActiveObjects ao = ao();
        if (ao == null) return false;
        if (issueKey == null || issueKey.trim().isEmpty()) return false;
        if (envelopeId == null || envelopeId.trim().isEmpty()) return false;
        if (payloadHash == null || payloadHash.trim().isEmpty()) return false;
        final String key = issueKey.trim();
        final String envId = envelopeId.trim();
        final String hash = payloadHash.trim();
        try {
            AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class,
                    Query.select().where("ISSUE_KEY = ? AND ENVELOPE_ID = ?", key, envId).order("ID DESC").limit(1));
            if (envs == null || envs.length == 0) return false;
            AoDocusignEnvelope env = envs[0];
            AoDocusignEvent[] events = ao.find(AoDocusignEvent.class,
                    Query.select().where("ENVELOPE_ID = ? AND PAYLOAD_HASH = ?", env.getID(), hash).limit(1));
            return events != null && events.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasEnvelope(String issueKey, String envelopeId) {
        ActiveObjects ao = ao();
        if (ao == null) return false;
        if (issueKey == null || issueKey.trim().isEmpty()) return false;
        if (envelopeId == null || envelopeId.trim().isEmpty()) return false;
        final String key = issueKey.trim();
        final String envId = envelopeId.trim();
        try {
            AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class,
                    Query.select().where("ISSUE_KEY = ? AND ENVELOPE_ID = ?", key, envId).limit(1));
            return envs != null && envs.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String findIssueKeyByEnvelopeId(String envelopeId) {
        ActiveObjects ao = ao();
        if (ao == null) return null;
        if (envelopeId == null || envelopeId.trim().isEmpty()) return null;
        final String envId = envelopeId.trim();
        try {
            AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class,
                    Query.select().where("ENVELOPE_ID = ?", envId).order("ID DESC").limit(1));
            if (envs == null || envs.length == 0) return null;
            String key = envs[0].getIssueKey();
            return (key != null && !key.trim().isEmpty()) ? key.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Records a Connect webhook event exactly once (based on payloadHash) and returns true if it was newly recorded.
     * This allows the REST webhook handler to be idempotent even when DocuSign retries quickly.
     */
    public static boolean recordConnectWebhookIfNew(String issueKey,
                                                    String envelopeId,
                                                    String envelopeStatus,
                                                    List<DocusignRecipientStatusService.RecipientStatus> recipients,
                                                    String payload,
                                                    String payloadHash) {
        ActiveObjects ao = ao();
        if (ao == null) return true; // can't dedupe; allow processing
        if (issueKey == null || issueKey.trim().isEmpty()) return true;
        if (envelopeId == null || envelopeId.trim().isEmpty()) return true;
        final String key = issueKey.trim();
        final String envId = envelopeId.trim();
        final String status = envelopeStatus != null ? envelopeStatus : "";
        final String payloadStr = payload;
        final String hash = (payloadHash != null && !payloadHash.trim().isEmpty()) ? payloadHash.trim() : null;

        try {
            Boolean inserted = ao.executeInTransaction(() -> {
                Date now = new Date();

                AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class, Query.select().where("ISSUE_KEY = ? AND ENVELOPE_ID = ?", key, envId).order("ID DESC").limit(1));
                AoDocusignEnvelope env = (envs != null && envs.length > 0) ? envs[0] : null;
                if (env == null) {
                    env = ao.create(AoDocusignEnvelope.class);
                    env.setIssueKey(key);
                    env.setEnvelopeId(envId);
                    env.setActive(true);
                    env.setCreatedAt(now);
                }

                env.setStatus(status);
                env.setUpdatedAt(now);
                env.save();

                if (recipients != null) {
                    for (DocusignRecipientStatusService.RecipientStatus r : recipients) {
                        if (r == null) continue;
                        String email = safe(r.getEmail());
                        if (email == null || email.isEmpty()) continue;
                        AoDocusignSigner[] ss = ao.find(AoDocusignSigner.class,
                                Query.select().where("ENVELOPE_ID = ? AND EMAIL = ?", env.getID(), email).limit(1));
                        if (ss != null && ss.length > 0) {
                            AoDocusignSigner s = ss[0];
                            s.setStatus(safe(r.getStatus()));
                            s.setUpdatedAt(now);
                            s.save();
                        }
                    }
                }

                if (hash != null) {
                    AoDocusignEvent[] existing = ao.find(AoDocusignEvent.class,
                            Query.select().where("ENVELOPE_ID = ? AND PAYLOAD_HASH = ?", env.getID(), hash).limit(1));
                    if (existing != null && existing.length > 0) {
                        return Boolean.FALSE;
                    }
                }

                AoDocusignEvent event = ao.create(AoDocusignEvent.class);
                event.setEnvelope(env);
                event.setEventType("webhook.connect");
                event.setEnvelopeStatus(status);
                event.setOccurredAt(now);
                event.setPayloadHash(hash);
                event.setPayload(payloadStr);
                event.save();
                return Boolean.TRUE;
            });
            return inserted != null ? inserted.booleanValue() : true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Build the state blob expected by the UI ({envelopeId,envelopeStatus,signerUiState}).
     * Returns null when AO isn't available or nothing is recorded.
     */
    public static JsonObject loadActiveIssueState(String issueKey) {
        ActiveObjects ao = ao();
        if (ao == null || issueKey == null || issueKey.trim().isEmpty()) return null;
        final String key = issueKey.trim();

        AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class, Query.select().where("ISSUE_KEY = ? AND ACTIVE = ?", key, true).order("ID DESC").limit(1));
        if (envs == null || envs.length == 0) return null;
        AoDocusignEnvelope env = envs[0];

        JsonObject out = new JsonObject();
        out.addProperty("envelopeId", safe(env.getEnvelopeId()));
        String envStatus = safe(env.getStatus());
        out.addProperty("envelopeStatus", envStatus);

        JsonArray signerUi = new JsonArray();
        AoDocusignSigner[] signers = ao.find(AoDocusignSigner.class, Query.select().where("ENVELOPE_ID = ?", env.getID()).order("ROUTING_ORDER ASC"));

        boolean forceAllCompleted = "completed".equalsIgnoreCase(envStatus);
        boolean activeSet = false;
        if (signers != null) {
            for (AoDocusignSigner s : signers) {
                if (s == null) continue;
                JsonObject obj = new JsonObject();
                obj.addProperty("name", safe(s.getName()));
                obj.addProperty("email", safe(s.getEmail()));
                obj.addProperty("routingOrder", s.getRoutingOrder() != null ? String.valueOf(s.getRoutingOrder()) : "");
                String raw = safe(s.getStatus());
                String st = raw != null ? raw.trim().toLowerCase() : "";
                String ui;
                if (forceAllCompleted || "completed".equals(st) || "signed".equals(st)) {
                    ui = "COMPLETED";
                } else if (!activeSet) {
                    ui = "CURRENT";
                    activeSet = true;
                } else {
                    ui = "PENDING";
                }
                obj.addProperty("uiStatus", ui);
                signerUi.add(obj);
            }
        }

        out.add("signerUiState", signerUi);
        return out;
    }

    public static void clearActiveEnvelope(String issueKey) {
        ActiveObjects ao = ao();
        if (ao == null || issueKey == null || issueKey.trim().isEmpty()) return;
        final String key = issueKey.trim();
        ao.executeInTransaction(() -> {
            Date now = new Date();
            for (AoDocusignEnvelope env : ao.find(AoDocusignEnvelope.class, Query.select().where("ISSUE_KEY = ? AND ACTIVE = ?", key, true))) {
                env.setActive(false);
                env.setUpdatedAt(now);
                env.save();
            }
            return null;
        });
    }

    /**
     * Returns a newest-first list of envelope history entries for an issue.
     */
    public static JsonArray loadIssueHistory(String issueKey, int limit) {
        ActiveObjects ao = ao();
        if (ao == null || issueKey == null || issueKey.trim().isEmpty()) return null;
        final String key = issueKey.trim();
        final int n = (limit <= 0 || limit > 50) ? 15 : limit;

        AoDocusignEnvelope[] envs = ao.find(AoDocusignEnvelope.class, Query.select().where("ISSUE_KEY = ?", key).order("ID DESC").limit(n));
        JsonArray arr = new JsonArray();
        if (envs == null) return arr;
        for (AoDocusignEnvelope env : envs) {
            if (env == null) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("envelopeId", safe(env.getEnvelopeId()));
            obj.addProperty("status", safe(env.getStatus()));
            obj.addProperty("active", env.isActive());
            obj.addProperty("createdAtMs", env.getCreatedAt() != null ? env.getCreatedAt().getTime() : 0L);
            obj.addProperty("updatedAtMs", env.getUpdatedAt() != null ? env.getUpdatedAt().getTime() : 0L);
            obj.addProperty("sender", safe(env.getSenderDisplayName()));
            arr.add(obj);
        }
        return arr;
    }

    /**
     * Return document metadata for a specific envelope (documentId + filename), when available.
     */
    public static List<DocumentMeta> loadEnvelopeDocuments(String issueKey, String envelopeId) {
        ActiveObjects ao = ao();
        if (ao == null) return null;
        if (issueKey == null || issueKey.trim().isEmpty()) return null;
        if (envelopeId == null || envelopeId.trim().isEmpty()) return null;
        String key = issueKey.trim();
        String envId = envelopeId.trim();

        AoDocusignEnvelope[] envs = ao.find(
                AoDocusignEnvelope.class,
                Query.select().where("ISSUE_KEY = ? AND ENVELOPE_ID = ?", key, envId).order("ID DESC").limit(1)
        );
        if (envs == null || envs.length == 0) return new ArrayList<>();
        AoDocusignEnvelope env = envs[0];

        AoDocusignDocument[] docs = ao.find(
                AoDocusignDocument.class,
                Query.select().where("ENVELOPE_ID = ?", env.getID()).order("DOCUMENT_ID ASC")
        );
        List<DocumentMeta> out = new ArrayList<>();
        if (docs == null) return out;
        for (AoDocusignDocument d : docs) {
            if (d == null) continue;
            String did = safe(d.getDocumentId());
            if (did == null || did.trim().isEmpty()) continue;
            out.add(new DocumentMeta(did.trim(), safe(d.getFilename())));
        }
        return out;
    }

    private static void createTab(ActiveObjects ao,
                                  AoDocusignSigner signer,
                                  String documentId,
                                  String pageNumber,
                                  String xPosition,
                                  String yPosition,
                                  int index,
                                  Date now) {
        if (ao == null || signer == null) return;
        AoDocusignTab tab = ao.create(AoDocusignTab.class);
        tab.setSigner(signer);
        tab.setTabType("signHere");
        tab.setDocumentId(req(documentId, "1"));
        tab.setPageNumber(parseIntSafe(pageNumber, 1));
        tab.setXPosition(parseIntSafe(xPosition, 400));
        tab.setYPosition(parseIntSafe(yPosition, 650));
        tab.setPositionIndex(index);
        tab.setCreatedAt(now);
        tab.save();
    }

    private static Long findAttachmentId(List<Long> requestedAttachmentIds, DocusignService.DocusignDocument d) {
        if (requestedAttachmentIds == null || d == null) return null;
        // We don't currently store attachmentId in the document payload, so map by order.
        try {
            int idx = parseIntSafe(d.documentId, -1) - 1;
            if (idx >= 0 && idx < requestedAttachmentIds.size()) {
                return requestedAttachmentIds.get(idx);
            }
        } catch (Exception ignore) {
        }
        // Fallback: first attachment
        return !requestedAttachmentIds.isEmpty() ? requestedAttachmentIds.get(0) : null;
    }

    private static int parseIntSafe(String val, int def) {
        try {
            if (val == null) return def;
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String req(String s, String def) {
        String v = safe(s);
        if (v != null) return v;
        return def != null ? def : "";
    }
}
