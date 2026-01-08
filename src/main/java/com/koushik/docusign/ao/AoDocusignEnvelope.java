package com.koushik.docusign.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

import java.util.Date;

@Preload
@Table("DOCUSIGN_ENVELOPE")
public interface AoDocusignEnvelope extends Entity {

    @NotNull
    @Indexed
    String getIssueKey();
    void setIssueKey(String issueKey);

    Long getIssueId();
    void setIssueId(Long issueId);

    @NotNull
    @Indexed
    String getEnvelopeId();
    void setEnvelopeId(String envelopeId);

    @NotNull
    boolean isActive();
    void setActive(boolean active);

    String getStatus();
    void setStatus(String status);

    String getSenderUserKey();
    void setSenderUserKey(String senderUserKey);

    String getSenderDisplayName();
    void setSenderDisplayName(String senderDisplayName);

    String getSenderEmail();
    void setSenderEmail(String senderEmail);

    Date getCreatedAt();
    void setCreatedAt(Date createdAt);

    Date getUpdatedAt();
    void setUpdatedAt(Date updatedAt);

    @StringLength(StringLength.UNLIMITED)
    String getSendRequestJson();
    void setSendRequestJson(String sendRequestJson);

    @StringLength(StringLength.UNLIMITED)
    String getSendResponseJson();
    void setSendResponseJson(String sendResponseJson);
}

