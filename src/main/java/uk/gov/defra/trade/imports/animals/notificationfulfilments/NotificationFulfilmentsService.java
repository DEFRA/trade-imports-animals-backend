package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;

/**
 * Thin service layer over the fulfilment-shape projection ({@link NotificationFulfilmentsView})
 * of the merged {@code notifications} collection. Serves the frontend journey-rehydrate read at
 * {@code GET /notification-fulfilments/{id}} via {@link NotificationFulfilmentsController}, so the
 * controller does not reach into the repository directly — matching the layering used by every
 * other controller in this backend.
 */
@Service
@RequiredArgsConstructor
public class NotificationFulfilmentsService {

    private static final String NOT_FOUND_MESSAGE_PREFIX =
        "Cannot find notification with reference number: ";

    private final NotificationRepository notificationRepository;

    /**
     * Return the fulfilment-view projection for the given reference number, or throw
     * {@link NotFoundException} if no notification exists at that reference.
     *
     * @param referenceNumber notification reference number (the URL path {@code {id}} on
     *     {@code GET /notification-fulfilments/{id}})
     * @return the fulfilment view; never {@code null}
     * @throws NotFoundException if no notification is found
     */
    public NotificationFulfilmentsView findByReferenceNumber(String referenceNumber) {
        return notificationRepository.findFulfilmentsViewByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE_PREFIX + referenceNumber));
    }
}
