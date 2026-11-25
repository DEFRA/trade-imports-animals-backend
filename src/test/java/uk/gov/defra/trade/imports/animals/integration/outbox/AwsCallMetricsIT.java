package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.S3_GET_OBJECT;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.SNS_PUBLISH;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.availability;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationSampleCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.outbox.OutboxPublishService;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

class AwsCallMetricsIT extends OutboxIntegrationBase {

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private OutboxPublishService outboxPublishService;
  @Autowired private S3DocumentService s3DocumentService;
  @Autowired private CdpConfig cdpConfig;

  @Test
  void publishUnpublishedEvents_shouldRecordASuccessfulSnsPublish() {
    createNewNotification("aws-call-metrics-it");
    long publishesBefore = durationSampleCount(meterRegistry, SNS_PUBLISH);
    double failuresBefore = failureTotal(meterRegistry, SNS_PUBLISH);

    assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(1);
    awaitSqsMessage();

    assertThat(durationSampleCount(meterRegistry, SNS_PUBLISH)).isEqualTo(publishesBefore + 1);
    assertThat(failureTotal(meterRegistry, SNS_PUBLISH)).isEqualTo(failuresBefore);
    assertThat(availability(meterRegistry, Dependency.SNS)).isEqualTo(1);
  }

  @Test
  void streamToOutput_shouldRecordAFailedGetObjectAndMarkS3Unavailable_whenTheKeyIsMissing() {
    ensureBucketExists(cdpConfig.s3().documentsBucket());
    double failuresBefore = failureTotal(meterRegistry, S3_GET_OBJECT);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    assertThatExceptionOfType(NoSuchKeyException.class)
        .isThrownBy(() -> s3DocumentService.streamToOutput("no-such-key", outputStream));

    assertThat(failureTotal(meterRegistry, S3_GET_OBJECT)).isEqualTo(failuresBefore + 1);
    assertThat(availability(meterRegistry, Dependency.S3)).isZero();
  }
}
