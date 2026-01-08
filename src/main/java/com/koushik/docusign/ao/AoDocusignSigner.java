package com.koushik.docusign.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Table;

import java.util.Date;

@Preload
@Table("DOCUSIGN_SIGNER")
public interface AoDocusignSigner extends Entity {
    @NotNull
    AoDocusignEnvelope getEnvelope();
    void setEnvelope(AoDocusignEnvelope envelope);

    @NotNull
    String getSignerType();
    void setSignerType(String signerType);

    // For JIRA_USER signers
    String getUserKey();
    void setUserKey(String userKey);

    @NotNull
    @Indexed
    String getEmail();
    void setEmail(String email);

    String getName();
    void setName(String name);

    @NotNull
    Integer getRoutingOrder();
    void setRoutingOrder(Integer routingOrder);

    @NotNull
    String getRecipientId();
    void setRecipientId(String recipientId);

    String getStatus();
    void setStatus(String status);

    Date getCreatedAt();
    void setCreatedAt(Date createdAt);

    Date getUpdatedAt();
    void setUpdatedAt(Date updatedAt);
}

