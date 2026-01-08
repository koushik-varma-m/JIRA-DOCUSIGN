package com.koushik.docusign.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Table;

import java.util.Date;

@Preload
@Table("DOCUSIGN_TAB")
public interface AoDocusignTab extends Entity {
    @NotNull
    AoDocusignSigner getSigner();
    void setSigner(AoDocusignSigner signer);

    @NotNull
    String getTabType();
    void setTabType(String tabType);

    @NotNull
    @Indexed
    String getDocumentId();
    void setDocumentId(String documentId);

    @NotNull
    Integer getPageNumber();
    void setPageNumber(Integer pageNumber);

    @NotNull
    Integer getXPosition();
    void setXPosition(Integer xPosition);

    @NotNull
    Integer getYPosition();
    void setYPosition(Integer yPosition);

    @NotNull
    Integer getPositionIndex();
    void setPositionIndex(Integer positionIndex);

    Date getCreatedAt();
    void setCreatedAt(Date createdAt);
}

