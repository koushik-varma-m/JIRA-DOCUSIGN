package com.koushik.docusign.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

import java.util.Date;

@Preload
@Table("DOCUSIGN_EVENT")
public interface AoDocusignEvent extends Entity {
    @NotNull
    AoDocusignEnvelope getEnvelope();
    void setEnvelope(AoDocusignEnvelope envelope);

    @NotNull
    @Indexed
    String getEventType();
    void setEventType(String eventType);

    String getEnvelopeStatus();
    void setEnvelopeStatus(String envelopeStatus);

    Date getOccurredAt();
    void setOccurredAt(Date occurredAt);

    @Indexed
    String getPayloadHash();
    void setPayloadHash(String payloadHash);

    @StringLength(StringLength.UNLIMITED)
    String getPayload();
    void setPayload(String payload);
}
