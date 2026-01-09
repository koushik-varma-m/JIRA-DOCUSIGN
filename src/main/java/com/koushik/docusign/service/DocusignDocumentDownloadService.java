package com.koushik.docusign.service;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.AttachmentManager;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Service responsible for attaching signed documents to Jira issues in an idempotent way.
 */
public class DocusignDocumentDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DocusignDocumentDownloadService.class);

    private final AttachmentManager attachmentManager;
    private final JiraAuthenticationContext authContext;

    public DocusignDocumentDownloadService() {
        this.attachmentManager = ComponentAccessor.getAttachmentManager();
        this.authContext = ComponentAccessor.getJiraAuthenticationContext();
    }

    /**
     * Attach a signed PDF to the issue if not already present.
     * Uses filename pattern: Signed_<original_filename>.pdf (or issue key if original is missing).
     *
     * @param issue            target issue
     * @param pdfBytes         signed PDF bytes
     * @param originalFilename original filename of the document (for naming); may be null/blank
     * @throws Exception on attachment creation failure
     */
    public void attachSignedPdfIfMissing(Issue issue, byte[] pdfBytes, String originalFilename) throws Exception {
        if (issue == null || pdfBytes == null) {
            return;
        }

        // Prefer provided original filename, else fall back to first issue attachment name, else issue key
        String baseName = (originalFilename != null && !originalFilename.trim().isEmpty())
                ? originalFilename.trim()
                : resolveFirstAttachmentName(issue);
        if (baseName == null || baseName.trim().isEmpty()) {
            baseName = issue.getKey();
        }
        String fileName = "Signed_" + baseName;
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }

        attachPdfIfMissing(issue, pdfBytes, fileName);
    }

    /**
     * Attach a PDF to an issue if an attachment with the same filename is not already present.
     */
    public boolean attachPdfIfMissing(Issue issue, byte[] pdfBytes, String fileName) throws Exception {
        if (issue == null || pdfBytes == null) return false;
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String targetName = fileName.trim();

        // Idempotency check: exact filename match only (supports multiple signed docs per issue).
        List<Attachment> attachments = attachmentManager.getAttachments(issue);
        if (attachments != null) {
            for (Attachment att : attachments) {
                if (att == null) continue;
                String name = att.getFilename();
                if (name == null) continue;
                if (name.equalsIgnoreCase(targetName)) {
                    log.info("Attachment already present for issue {}. Skipping duplicate: {}", issue.getKey(), name);
                    return true;
                }
            }
        }

        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        File temp = writeTempFile(pdfBytes);
        try {
            attachmentManager.createAttachment(temp, targetName, "application/pdf", user, issue);
            log.info("Attached PDF {} to issue {}", targetName, issue.getKey());
            return true;
        } finally {
            if (temp != null && temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * Attach any file bytes to the issue if an attachment with the same filename is not already present.
     */
    public boolean attachFileIfMissing(Issue issue, byte[] bytes, String fileName, String contentType) throws Exception {
        if (issue == null || bytes == null) return false;
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String targetName = fileName.trim();
        String mime = (contentType != null && !contentType.trim().isEmpty()) ? contentType.trim() : "application/octet-stream";

        List<Attachment> attachments = attachmentManager.getAttachments(issue);
        if (attachments != null) {
            for (Attachment att : attachments) {
                if (att == null) continue;
                String name = att.getFilename();
                if (name == null) continue;
                if (name.equalsIgnoreCase(targetName)) {
                    log.info("Attachment already present for issue {}. Skipping duplicate: {}", issue.getKey(), name);
                    return true;
                }
            }
        }

        ApplicationUser user = authContext != null ? authContext.getLoggedInUser() : null;
        File temp = writeTempFile(bytes);
        try {
            attachmentManager.createAttachment(temp, targetName, mime, user, issue);
            log.info("Attached file {} ({}) to issue {}", targetName, mime, issue.getKey());
            return true;
        } finally {
            if (temp != null && temp.exists()) {
                temp.delete();
            }
        }
    }

    private File writeTempFile(byte[] data) throws IOException {
        File temp = File.createTempFile("docusign-signed-", ".pdf");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(data);
            fos.flush();
        }
        return temp;
    }

    private String resolveFirstAttachmentName(Issue issue) {
        try {
            List<Attachment> attachments = attachmentManager.getAttachments(issue);
            if (attachments != null && !attachments.isEmpty()) {
                Attachment first = attachments.get(0);
                if (first != null && first.getFilename() != null) {
                    return first.getFilename();
                }
            }
        } catch (Exception e) {
            log.debug("Unable to resolve first attachment name for {}: {}", issue != null ? issue.getKey() : "null", e.getMessage());
        }
        return null;
    }
}
