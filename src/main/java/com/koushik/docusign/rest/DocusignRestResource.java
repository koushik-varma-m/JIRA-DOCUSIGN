package com.koushik.docusign.rest;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.AttachmentManager;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.jira.util.io.InputStreamConsumer;
import com.koushik.docusign.docusign.DocusignService;
import com.koushik.docusign.docusign.DocusignService.DocuSignDocument;
import com.koushik.docusign.docusign.DocusignService.DocuSignSigner;
import org.apache.commons.io.IOUtils;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

@Path("/send")
public class DocusignRestResource {

    private final IssueManager issueManager = ComponentAccessor.getIssueManager();

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendDocument(SendRequest request) {
        try {

            if (request == null || request.getIssueKey() == null || request.getIssueKey().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Missing or invalid issue key\"}")
                        .build();
            }

            Issue issue = issueManager.getIssueObject(request.getIssueKey());
            if (issue == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Invalid issue key\"}")
                        .build();
            }

            AttachmentManager attachmentManager = ComponentAccessor.getAttachmentManager();
            List<Attachment> attachments = attachmentManager.getAttachments(issue);

            List<AttachmentData> result = new ArrayList<>();

            for (Attachment attachment : attachments) {
                try {
                    byte[] fileBytes = attachmentManager.streamAttachmentContent(attachment, (InputStream inputStream) -> {
                        try {
                            return IOUtils.toByteArray(inputStream);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read attachment stream", e);
                        }
                    });
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    result.add(new AttachmentData(attachment.getFilename(), base64));
                } catch (Exception e) {
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Failed to read attachment: " + attachment.getFilename() + "\"}")
                        .build();
                }
            }

            if (result.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Issue has no attachments\"}")
                        .build();
            }

            List<DocuSignDocument> docs = convertToDocusignDocuments(result);

            List<DocuSignSigner> signers = new ArrayList<>();
            signers.add(new DocuSignSigner("testsigner@example.com", "Test Signer", "1", "1"));

            DocusignService ds = new DocusignService();
            String envelopeId;

            try {
                envelopeId = ds.sendEnvelope(issue.getKey(), docs, signers);
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
            }

            return Response.ok("{\"envelopeId\": \"" + envelopeId + "\"}").build();
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Internal server error";
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"" + errorMsg + "\"}")
                    .build();
        }
    }

    public static class SendRequest {
        private String issueKey;

        public SendRequest() {              
        }

        public String getIssueKey() {
            return issueKey;
        }

        public void setIssueKey(String issueKey) {
            this.issueKey = issueKey;
        }
    }

    private List<DocuSignDocument> convertToDocusignDocuments(List<AttachmentData> attachments) {
        List<DocuSignDocument> docs = new ArrayList<>();
        int docId = 1;

        for (AttachmentData a : attachments) {
            docs.add(new DocuSignDocument(a.getBase64(), a.getFilename(), String.valueOf(docId++)));
        }

        return docs;
    }

    private List<DocuSignSigner> convertSigners(List<SignerRequest> signerRequests) {
        List<DocuSignSigner> signers = new ArrayList<>();

        for (SignerRequest req : signerRequests) {
            signers.add(new DocuSignSigner(
                req.getEmail(),
                req.getName(),
                req.getRecipientId(),
                req.getRoutingOrder()
            ));
        }

        return signers;
    }

    public static class AttachmentData {
        private String filename;
        private String base64;

        public AttachmentData(String filename, String base64) {
            this.filename = filename;
            this.base64 = base64;
        }

        public String getFilename() {
            return filename;
        }

        public String getBase64() {
            return base64;
        }
    }

    public static class SignerRequest {
        private String name;
        private String email;
        private String recipientId;
        private String routingOrder;

        public SignerRequest() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getRecipientId() {
            return recipientId;
        }

        public void setRecipientId(String recipientId) {
            this.recipientId = recipientId;
        }

        public String getRoutingOrder() {
            return routingOrder;
        }

        public void setRoutingOrder(String routingOrder) {
            this.routingOrder = routingOrder;
        }
    }
}
