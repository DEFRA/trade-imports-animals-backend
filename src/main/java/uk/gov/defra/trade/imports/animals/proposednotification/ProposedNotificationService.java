package uk.gov.defra.trade.imports.animals.proposednotification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProposedNotificationService {

    private static final String CANNOT_FIND_PROPOSED_NOTIFICATION_WITH_ID =
        "Cannot find proposed notification with id: ";

    private final ProposedNotificationRepository proposedNotificationRepository;

    public ReplaceResult replace(String id, Document body) {
        if (!id.equals(body.get("referenceNumber"))) {
            throw new BadRequestException(
                "Path id and proposed notification body reference number must match");
        }

        ProposedNotification proposedNotification =
            proposedNotificationRepository.findById(id).orElse(null);
        boolean created = proposedNotification == null;
        if (created) {
            proposedNotification = ProposedNotification.builder()
                .id(id)
                .build();
        }
        proposedNotification.setBody(body);
        ProposedNotification saved = proposedNotificationRepository.save(proposedNotification);
        log.info("{} proposed notification {}", created ? "Created" : "Replaced", id);
        return new ReplaceResult(saved.getBody(), created);
    }

    public Document findById(String id) {
        return proposedNotificationRepository.findById(id)
            .map(ProposedNotification::getBody)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_PROPOSED_NOTIFICATION_WITH_ID + id));
    }

    public record ReplaceResult(Document body, boolean created) {

    }
}
