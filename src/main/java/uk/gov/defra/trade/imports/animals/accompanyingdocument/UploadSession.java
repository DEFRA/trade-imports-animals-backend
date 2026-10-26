package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document recording a cdp-uploader upload session, before anything is known about the
 * document that might arrive through it.
 *
 * <p>Expires on {@code created} after SESSION_RETENTION_PERIOD. Why a session and a document are
 * separate records, and why 24 hours, are in {@code docs/design-notes.md}.
 */
@Document(collection = "document_upload_sessions")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
public class UploadSession {

  private static final String SESSION_RETENTION_PERIOD = "24h";

  @EqualsAndHashCode.Include
  @Id
  private String id;

  /** The notification the document would belong to, were one to arrive. */
  @Indexed
  private String notificationReferenceNumber;

  /** Identifier assigned by cdp-uploader when the session was opened. */
  @Indexed(unique = true)
  private String uploadId;

  /**
   * Opaque identifier minted for this session and threaded through cdp-uploader's {@code metadata}
   * map, which it echoes back verbatim in the scan callback. This is what resolves the callback to
   * its notification.
   */
  @Indexed(unique = true)
  private String correlationId;

  @CreatedDate
  @Indexed(expireAfter = SESSION_RETENTION_PERIOD)
  private Instant created;
}
