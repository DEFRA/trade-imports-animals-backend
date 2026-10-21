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
 * MongoDB document recording a cdp-uploader upload session the uploading journey has opened,
 * before anything is known about the document that might arrive through it.
 *
 * <p>Deliberately not an {@link AccompanyingDocument}. A session is opened every time the upload
 * page is painted, whether or not the trader goes on to add a file, so recording one as a document
 * would leave a notification holding a document per page view. A document is written when one of
 * the two things that mean a real file exists happens: the trader's details arrive, or cdp-uploader
 * reports a scan. Until then the session is only the handle that lets either of those be matched to
 * its notification, which the callback cannot do for itself — cdp-uploader echoes the metadata it
 * was given but does not include {@code uploadId} in the callback body.
 *
 * <p>Sessions are short-lived by nature and expire on {@code created}, so the ones nobody uploaded
 * through do not accumulate.
 */
@Document(collection = "document_upload_sessions")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
public class UploadSession {

  /** How long a session is kept. Long enough for any scan to come back, short enough to forget. */
  private static final String RETENTION = "24h";

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
  @Indexed(expireAfter = RETENTION)
  private Instant created;
}
