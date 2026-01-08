package com.koushik.docusign.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Table;

import java.util.Date;

@Preload
@Table("DOCUSIGN_DOCUMENT")
public interface AoDocusignDocument extends Entity {
    @NotNull
    AoDocusignEnvelope getEnvelope();
    void setEnvelope(AoDocusignEnvelope envelope);

    @NotNull
    @Indexed
    Long getAttachmentId();
    void setAttachmentId(Long attachmentId);

    String getFilename();
    void setFilename(String filename);

    @NotNull
    String getDocumentId();
    void setDocumentId(String documentId);

    Date getCreatedAt();
    void setCreatedAt(Date createdAt);
}

