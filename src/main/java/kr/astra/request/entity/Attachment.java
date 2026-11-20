package kr.astra.request.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 의뢰 첨부파일 (V1 attachments + V16 category). 파일 자체는 LocalFileStorage 에, 여기에는 위치만 */
@Getter
@Entity
@Table(name = "attachments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "revision_no")
    private Integer revisionNo;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttachmentCategory category;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Attachment(Long requestId, Integer revisionNo, String fileName, String storageKey, String mimeType,
                      long sizeBytes, Long uploadedBy, AttachmentCategory category) {
        this.requestId = requestId;
        this.revisionNo = revisionNo;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.category = category;
    }
}
