package uk.gov.defra.trade.imports.animals.template;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private final NotificationTemplateRepository notificationTemplateRepository;

    /**
     * Lists the templates one user has saved, newest first.
     *
     * @param ownerId the user whose templates to list
     * @return a summary of each of the user's templates, and how many there are
     */
    public NotificationTemplateListResponse listFor(String ownerId) {
        List<NotificationTemplateSummary> items = notificationTemplateRepository
            .findAllByOwnerIdOrderByCreatedDesc(ownerId).stream()
            .map(NotificationTemplateSummary::from)
            .toList();
        return new NotificationTemplateListResponse(items, items.size());
    }
}
