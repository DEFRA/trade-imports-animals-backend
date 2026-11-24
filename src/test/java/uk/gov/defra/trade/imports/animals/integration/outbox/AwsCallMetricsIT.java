package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.availability;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCall;
import uk.gov.defra.trade.imports.animals.outbox.OutboxPublishService;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

class AwsCallMetricsIT extends OutboxIntegrationBase {

  private static final ExternalCall SNS_PUBLISH = new ExternalCall(Dependency.SNS, "publish");
  private static final ExternalCall S3_GET_OBJECT = new ExternalCall(Dependency.S3, "get-object");

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private OutboxPublishService outboxPublishService;
  @Autowired private S3DocumentService s3DocumentService;

  private int publishedMessages;

  @AfterEach
  void drainTheQueue() {
    awaitSqsMessages(publishedMessages);
    purgeQueue();
  }

  @Test
  void anOutboxPublishIsRecordedAsASuccessfulSnsPublish() {
    createNewNotification("aws-call-metrics-it");
    long before = durationCount(meterRegistry, SNS_PUBLISH);
    double failuresBefore = failureTotal(meterRegistry, SNS_PUBLISH);

    publishedMessages = outboxPublishService.publishUnpublishedEvents();

    assertThat(publishedMessages).isPositive();
    assertThat(durationCount(meterRegistry, SNS_PUBLISH)).isEqualTo(before + publishedMessages);
    assertThat(failureTotal(meterRegistry, SNS_PUBLISH)).isEqualTo(failuresBefore);
    assertThat(availability(meterRegistry, Dependency.SNS)).isEqualTo(1);
  }

  @Test
  void aDocumentReadThatS3RefusesIsRecordedAsAFailedGetObject() {
    double before = failureTotal(meterRegistry, S3_GET_OBJECT);

    assertThatExceptionOfType(S3Exception.class)
        .isThrownBy(
            () -> s3DocumentService.streamToOutput("no-such-key", new ByteArrayOutputStream()));

    assertThat(failureTotal(meterRegistry, S3_GET_OBJECT)).isEqualTo(before + 1);
    assertThat(availability(meterRegistry, Dependency.S3)).isZero();
  }
}
