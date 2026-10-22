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
 * MongoDB document representing an accompanying document submitted alongside an import
 * notification. One record exists per upload that produced either the trader's details or a scan
 * result, written by whichever of the two arrives first.
 *
 * <p>{@code dateOfIssue} is a calendar date, so it is a {@link LocalDate}: {@code YYYY-MM-DD} on
 * the wire and a string in MongoDB, written and read by {@code LocalDateStringConverters}. The
 * moments {@code created} and {@code updated} are {@link Instant}, which MongoDB stores natively.
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

  /** Optimistic locking version managed by Spring Data MongoDB. */
  @Version
  private Long version;

  /** Foreign key to the parent notification. Not unique — one notification may have many docs. */
  @Indexed
  private String notificationReferenceNumber;

  /** Unique identifier assigned by cdp-uploader at initiation time. */
  @Indexed(unique = true)
  private String uploadId;

  /** The handle the upload session was opened under. */
  @Indexed(unique = true)
  private String correlationId;

  private DocumentType documentType;

  private String documentReference;

  /** Date of issue on the physical document, stored as a string such as {@code 2024-06-15}. */
  private LocalDate dateOfIssue;

  private ScanStatus scanStatus;

  /** Whether the trader's document details have been supplied for this upload. */
  @Builder.Default
  private boolean claimed = false;

  /** Individual file entries populated by cdp-uploader callbacks. Initialised to empty list. */
  @Builder.Default
  private List<UploadedFile> files = new ArrayList<>();

  @CreatedDate
  private Instant created;

  @LastModifiedDate
  private Instant updated;
}
