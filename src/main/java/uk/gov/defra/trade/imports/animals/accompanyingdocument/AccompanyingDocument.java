package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
/**
 * An accompanying document submitted alongside an import notification. One record exists per
 * upload that produced either the trader's details or a scan result.
 */
@CompoundIndex(def = "{'notificationReferenceNumber': 1, 'scanStatus': 1, 'claimed': 1, 'created': 1}")
@Document(collection = "accompanying_documents")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
public class AccompanyingDocument {

  @EqualsAndHashCode.Include
  @Id
  private String id;

  @Version
  private Long version;

  @Indexed
  private String notificationReferenceNumber;

  @Indexed(unique = true)
  private String uploadId;

  @Indexed(unique = true)
  private String correlationId;

  private DocumentType documentType;

  private String documentReference;

  /** Date of issue on the physical document. */
  private LocalDate dateOfIssue;

  private ScanStatus scanStatus;

  @Builder.Default
  private boolean claimed = false;

  @Builder.Default
  private List<UploadedFile> files = new ArrayList<>();

  @CreatedDate
  private Instant created;

  @LastModifiedDate
  private Instant updated;

  private Instant expireAt;
}
