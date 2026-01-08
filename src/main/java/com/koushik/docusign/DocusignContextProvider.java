package com.koushik.docusign;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.AttachmentManager;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.plugin.web.ContextProvider;
import com.koushik.docusign.service.JiraUserService;
import com.atlassian.jira.bc.issue.properties.IssuePropertyService;
import com.atlassian.jira.entity.property.EntityPropertyService.PropertyResult;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koushik.docusign.config.DocusignConfig;
import com.koushik.docusign.persistence.DocusignAoStore;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DocusignContextProvider implements ContextProvider {

    private static final Gson GSON = new Gson();

    @Override
    public void init(Map<String, String> params) {
    }

    @Override
    public Map<String, Object> getContextMap(Map<String, Object> context) {
        Map<String, Object> map = new HashMap<>();
        IssuePropertyService issuePropertyService = ComponentAccessor.getComponent(IssuePropertyService.class);
        JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        
        try {
            Issue issue = (Issue) context.get("issue");
            if (issue != null) {
                String issueKey = issue.getKey();
                map.put("issueKey", issueKey != null ? issueKey : "");
                
                // Get attachments from the issue
                try {
                    AttachmentManager attachmentManager = ComponentAccessor.getAttachmentManager();
                    List<Attachment> attachments = attachmentManager.getAttachments(issue);
                    
                    // Create list of attachment info (id and filename) for the template
                    List<Map<String, Object>> attachmentList = new ArrayList<>();
                    if (attachments != null) {
                        for (Attachment attachment : attachments) {
                            if (attachment != null) {
                                Map<String, Object> attachmentInfo = new HashMap<>();
                                attachmentInfo.put("id", attachment.getId());
                                String filename = attachment.getFilename();
                                attachmentInfo.put("filename", filename != null ? filename : "");
                                attachmentList.add(attachmentInfo);
                            }
                        }
                    }
                    map.put("attachments", attachmentList);
                } catch (Exception e) {
                    // If attachment retrieval fails, just return empty list
                    map.put("attachments", new ArrayList<>());
                }

                // Persisted DocuSign state to drive UI
                String signerUiState = "";
                String envelopeStatus = "";
                String envelopeId = "";
                try {
                    JsonObject aoState = DocusignAoStore.loadActiveIssueState(issueKey);
                    if (aoState != null) {
                        envelopeId = aoState.has("envelopeId") && aoState.get("envelopeId").isJsonPrimitive() ? aoState.get("envelopeId").getAsString() : "";
                        envelopeStatus = aoState.has("envelopeStatus") && aoState.get("envelopeStatus").isJsonPrimitive() ? aoState.get("envelopeStatus").getAsString() : "";
                        if (aoState.has("signerUiState") && aoState.get("signerUiState").isJsonArray()) {
                            signerUiState = aoState.get("signerUiState").toString();
                        }
                    }
                } catch (Exception ignore) {
                }
                if (envelopeId == null || envelopeId.trim().isEmpty()) {
                    signerUiState = readIssueProperty(issuePropertyService, user, issue.getId(), "docusign.signerUiState");
                    envelopeStatus = readIssueProperty(issuePropertyService, user, issue.getId(), "docusign.envelopeStatus");
                    envelopeId = readIssueProperty(issuePropertyService, user, issue.getId(), "docusign.envelopeId");
                }

                map.put("signerUiState", signerUiState);
                map.put("envelopeStatus", envelopeStatus);
                map.put("envelopeId", envelopeId);
                map.put("signerUiStateJs", escapeForJs(signerUiState));
                map.put("envelopeStatusJs", escapeForJs(envelopeStatus));
                map.put("envelopeIdJs", escapeForJs(envelopeId));
                map.put("webhookEnabled", isWebhookEnabled());
                map.put("assigneeUserKey", issue.getAssignee() != null && issue.getAssignee().getKey() != null ? issue.getAssignee().getKey() : "");

                // Fetch assignable project users (no email exposed)
                try {
                    JiraUserService userService = new JiraUserService();
                    List<Map<String, Object>> projectUsers = new ArrayList<>();
                    for (JiraUserService.JiraUser jiraUser : userService.getAssignableUsersForIssue(issue)) {
                        Map<String, Object> userInfo = new HashMap<>();
                        userInfo.put("userKey", jiraUser.getUserKey());
                        userInfo.put("displayName", jiraUser.getDisplayName());
                        projectUsers.add(userInfo);
                    }
                    // Ensure assignee/reporter/current user are always present (common Jira setups use groups, so role expansion can miss them).
                    Set<String> existing = new HashSet<>();
                    for (Map<String, Object> u : projectUsers) {
                        if (u == null) continue;
                        Object key = u.get("userKey");
                        if (key != null) {
                            existing.add(String.valueOf(key));
                        }
                    }
                    List<Map<String, Object>> pinned = new ArrayList<>();
                    addIfMissing(pinned, existing, issue.getAssignee());
                    addIfMissing(pinned, existing, issue.getReporter());
                    addIfMissing(pinned, existing, user);
                    if (!pinned.isEmpty()) {
                        pinned.addAll(projectUsers);
                        projectUsers = pinned;
                    }
                    map.put("projectUsers", projectUsers);
                    try {
                        // Use JSON to avoid Velocity foreach comma issues and to ensure safe JS parsing.
                        String json = GSON.toJson(projectUsers);
                        map.put("projectUsersJsonJs", escapeForJs(json));
                        map.put("projectUsersJsonB64", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        map.put("projectUsersJsonJs", "");
                        map.put("projectUsersJsonB64", "");
                    }
                } catch (Exception e) {
                    map.put("projectUsers", new ArrayList<>());
                    map.put("projectUsersJsonJs", "");
                    map.put("projectUsersJsonB64", "");
                }
            } else {
                map.put("issueKey", "");
                map.put("attachments", new ArrayList<>());
                map.put("projectUsers", new ArrayList<>());
                map.put("projectUsersJsonJs", "");
                map.put("projectUsersJsonB64", "");
                map.put("signerUiState", "");
                map.put("envelopeStatus", "");
                map.put("envelopeId", "");
                map.put("signerUiStateJs", "");
                map.put("envelopeStatusJs", "");
                map.put("envelopeIdJs", "");
                map.put("webhookEnabled", isWebhookEnabled());
                map.put("assigneeUserKey", "");
            }
        } catch (Exception e) {
            // If anything fails, return safe defaults
            map.put("issueKey", "");
            map.put("attachments", new ArrayList<>());
            map.put("projectUsers", new ArrayList<>());
            map.put("projectUsersJsonJs", "");
            map.put("projectUsersJsonB64", "");
            map.put("signerUiState", "");
            map.put("envelopeStatus", "");
            map.put("envelopeId", "");
            map.put("signerUiStateJs", "");
            map.put("envelopeStatusJs", "");
            map.put("envelopeIdJs", "");
            map.put("webhookEnabled", isWebhookEnabled());
            map.put("assigneeUserKey", "");
        }

        return map;
    }

    private boolean isWebhookEnabled() {
        String v = DocusignConfig.getString("DOCUSIGN_WEBHOOK_URL", "");
        return v != null && !v.trim().isEmpty();
    }

    private void addIfMissing(List<Map<String, Object>> into, Set<String> existingKeys, ApplicationUser user) {
        if (into == null || existingKeys == null || user == null) {
            return;
        }
        String userKey = user.getKey();
        if (userKey == null || userKey.trim().isEmpty() || existingKeys.contains(userKey)) {
            return;
        }
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userKey", userKey);
        String displayName = user.getDisplayName();
        userInfo.put("displayName", displayName != null ? displayName : userKey);
        into.add(userInfo);
        existingKeys.add(userKey);
    }

    private String readIssueProperty(IssuePropertyService service, ApplicationUser user, Long issueId, String key) {
        if (service == null || issueId == null || key == null) {
            return "";
        }
        PropertyResult result = service.getProperty(user, issueId, key);
        if (result == null || !result.getEntityProperty().isDefined()) {
            return "";
        }
        String raw = result.getEntityProperty().get().getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return "";
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
            return raw;
        }
    }

    private String escapeForJs(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</", "<\\/");
    }
}
