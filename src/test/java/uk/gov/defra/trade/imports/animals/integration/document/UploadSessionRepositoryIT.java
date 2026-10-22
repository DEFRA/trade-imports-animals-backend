package uk.gov.defra.trade.imports.animals.integration.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSession;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionRepository;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;

/**
 * Integration test for {@link UploadSessionRepository} against a real MongoDB.
 *
 * <p>Covers what no other test reaches: that sessions expire, that the unique indexes on
 * {@code uploadId} and {@code correlationId} are each materialised in isolation, and that
 * {@code deleteAllByNotificationReferenceNumberIn} selects the rows it names.
 */
class UploadSessionRepositoryIT extends IntegrationBase {

  private static final String SESSION_RETENTION_SECONDS = "86400";

  @Autowired
  private UploadSessionRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
  }

  private static UploadSession session(String uploadId, String correlationId, String ref) {
    return UploadSession.builder()
        .uploadId(uploadId)
        .correlationId(correlationId)
        .notificationReferenceNumber(ref)
        .build();
  }

  /**
   * Sessions are expired by MongoDB, not by any code this suite runs, so without this the TTL
   * index could be dropped and every other test would still pass while sessions accumulated.
   */
  @Test
  void created_shouldCarryATtlIndex_soSessionsNobodyUploadedThroughExpire() {
    assertThat(mongoTemplate.indexOps(UploadSession.class).getIndexInfo())
        .anySatisfy(index -> {
          assertThat(index.getIndexFields())
              .singleElement()
              .satisfies(field -> assertThat(field.getKey()).isEqualTo("created"));
          assertThat(index.getExpireAfter())
              .isPresent()
              .get()
              .satisfies(expiry ->
                  assertThat(expiry.toSeconds()).isEqualTo(Long.parseLong(SESSION_RETENTION_SECONDS)));
        });
  }

  @Test
  void save_shouldThrowDuplicateKeyException_whenUploadIdAlreadyExists() {
    repository.save(session("dup-upload", "uniq-corr-A", "GBN-AG-26-USR001"));

    assertThatThrownBy(() -> repository.save(session("dup-upload", "uniq-corr-B", "GBN-AG-26-USR002")))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(repository.findByUploadId("dup-upload"))
        .isPresent()
        .get()
        .satisfies(stored ->
            assertThat(stored.getNotificationReferenceNumber()).isEqualTo("GBN-AG-26-USR001"));
  }

  @Test
  void save_shouldThrowDuplicateKeyException_whenCorrelationIdAlreadyExists() {
    repository.save(session("uniq-upload-A", "dup-corr", "GBN-AG-26-USR003"));

    assertThatThrownBy(() -> repository.save(session("uniq-upload-B", "dup-corr", "GBN-AG-26-USR004")))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(repository.findByCorrelationId("dup-corr"))
        .isPresent()
        .get()
        .satisfies(stored ->
            assertThat(stored.getNotificationReferenceNumber()).isEqualTo("GBN-AG-26-USR003"));
  }

  @Test
  void deleteAllByNotificationReferenceNumberIn_shouldDeleteOnlyTheNamedNotificationsSessions() {
    repository.save(session("del-upload-1", "del-corr-1", "GBN-AG-26-DEL001"));
    repository.save(session("del-upload-2", "del-corr-2", "GBN-AG-26-DEL001"));
    repository.save(session("keep-upload", "keep-corr", "GBN-AG-26-KEEP01"));

    repository.deleteAllByNotificationReferenceNumberIn(List.of("GBN-AG-26-DEL001"));

    assertThat(repository.findByUploadId("del-upload-1")).isEmpty();
    assertThat(repository.findByUploadId("del-upload-2")).isEmpty();
    assertThat(repository.findByUploadId("keep-upload")).isPresent();
  }

  @Test
  void findByNotificationReferenceNumberAndCorrelationId_shouldNotReachAnotherNotificationsSession() {
    repository.save(session("scope-upload", "scope-corr", "GBN-AG-26-SCP001"));

    assertThat(repository
        .findByNotificationReferenceNumberAndCorrelationId("GBN-AG-26-SCP001", "scope-corr"))
        .isPresent();
    assertThat(repository
        .findByNotificationReferenceNumberAndCorrelationId("GBN-AG-26-OTHER1", "scope-corr"))
        .isEmpty();
  }
}
