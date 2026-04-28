package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
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
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representing an accompanying document submitted alongside an import
 * notification. One record exists per upload initiation; the embedded {@code files} list is
 * populated by cdp-uploader callbacks.
 *
 * <p>{@code dateOfIssue} is stored as an {@link Instant} (UTC epoch millis in MongoDB). We use
 * {@code Instant} rather than {@code LocalDate} because: (a) we want unambiguous UTC storage with
 * no timezone conversion, and (b) the cdp-uploader callback provides ISO-8601 timestamps.
 * Consumers that need a date-only view should truncate to the date component at presentation time.
 *
 * <p>{@code Instant} does not require a custom MongoDB codec — Spring Data MongoDB 3.x includes
 * native {@code Instant} codec support via its {@code InstantCodec} registered in the
 * {@code MongoMappingContext}.
 */
@CompoundIndexes({
  @CompoundIndex(def = "{'notificationReferenceNumber': 1, 'scanStatus': 1}"),
  @CompoundIndex(
      def = "{'notificationReferenceNumber': 1}",
      unique = true,
      partialFilter = "{scanStatus: {$eq: 'PENDING'}}")
})
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

  /** The cdp-uploader form URL returned at initiation; stored for idempotent re-requests. */
  private String uploadUrl;

  private DocumentType documentType;

  private String documentReference;

  /**
   * Date of issue on the physical document. Stored as UTC {@link Instant}; see class-level Javadoc
   * for the rationale for choosing {@code Instant} over {@code LocalDate}.
   */
  private Instant dateOfIssue;

  private ScanStatus scanStatus;

  /** Individual file entries populated by cdp-uploader callbacks. Initialised to empty list. */
  @Builder.Default
  private List<UploadedFile> files = new ArrayList<>();

  @CreatedDate
  private Instant created;

  @LastModifiedDate
  private Instant updated;
}
