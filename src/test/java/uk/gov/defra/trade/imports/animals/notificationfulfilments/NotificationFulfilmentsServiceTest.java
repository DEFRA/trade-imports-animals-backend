package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

@ExtendWith(MockitoExtension.class)
class NotificationFulfilmentsServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationFulfilmentsService service;

    @Test
    void findByReferenceNumber_shouldReturnView_whenFound() {
        String ref = "GBN-AG-26-FULF01";
        NotificationFulfilmentsView view = fulfilmentsView(ref);
        when(notificationRepository.findFulfilmentsViewByReferenceNumber(ref))
            .thenReturn(Optional.of(view));

        NotificationFulfilmentsView result = service.findByReferenceNumber(ref);

        assertThat(result).isSameAs(view);
    }

    @Test
    void findByReferenceNumber_shouldThrowNotFound_whenAbsent() {
        String ref = "GBN-AG-26-ABSENT";
        when(notificationRepository.findFulfilmentsViewByReferenceNumber(ref))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByReferenceNumber(ref))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(ref);
    }

    private static NotificationFulfilmentsView fulfilmentsView(String ref) {
        return new NotificationFulfilmentsView() {
            @Override public String getId() { return ref; }
            @Override public NotificationStatus getStatus() { return NotificationStatus.DRAFT; }
            @Override public LocalDateTime getCreatedAt() { return LocalDateTime.of(2026, 4, 15, 10, 0); }
            @Override public LocalDateTime getSubmittedAt() { return null; }
            @Override public List<Document> getFulfilments() { return List.of(); }
        };
    }
}
