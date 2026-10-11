package uk.gov.defra.trade.imports.animals.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private final NotificationTemplateRepository notificationTemplateRepository;

    /**
     * Lists the templates one user has saved, newest saved first; rows saved before the saved time
     * was recorded come after, newest created first. A template still being created is not listed.
     *
     * @param ownerId the user whose templates to list
     * @return a summary of each of the user's saved templates, and how many there are
     */
    public NotificationTemplateListResponse listFor(String ownerId) {
        List<NotificationTemplateSummary> items = notificationTemplateRepository
            .findAllByOwnerIdAndStatusNotOrderBySavedDescCreatedDesc(ownerId, TemplateStatus.IN_PROGRESS)
            .stream()
            .map(NotificationTemplateSummary::from)
            .toList();
        return new NotificationTemplateListResponse(items, items.size());
    }

    /**
     * Starts a template for one user, held as in progress until it is saved.
     *
     * @param ownerId the user who owns the new template
     * @param request the journey set and name to record
     * @return the template as stored
     */
    public NotificationTemplateResponse start(String ownerId, StartTemplateRequest request) {
        NotificationTemplate template = NotificationTemplate.builder()
            .ownerId(ownerId)
            .organisationId(request.organisationId())
            .setId(request.setId())
            .name(request.name())
            .status(TemplateStatus.IN_PROGRESS)
            .build();
        return NotificationTemplateResponse.from(notificationTemplateRepository.save(template));
    }

    /**
     * Finds one template owned by a user.
     *
     * @param ownerId the user who owns the template
     * @param id the template's identifier
     * @return the template
     * @throws NotFoundException when there is no such template, or another user owns it
     */
    public NotificationTemplateResponse get(String ownerId, String id) {
        return NotificationTemplateResponse.from(load(ownerId, id));
    }

    /**
     * Records a new journey set and name on a template.
     *
     * @param ownerId the user who owns the template
     * @param id the template's identifier
     * @param request the journey set and name to record
     * @return the template as stored
     * @throws NotFoundException when there is no such template, or another user owns it
     */
    public NotificationTemplateResponse updateDetails(
        String ownerId, String id, TemplateDetailsRequest request) {
        NotificationTemplate template = load(ownerId, id);
        template.setSetId(request.setId());
        template.setName(request.name());
        return NotificationTemplateResponse.from(notificationTemplateRepository.save(template));
    }

    /**
     * Replaces the answers a template holds.
     *
     * @param ownerId the user who owns the template
     * @param id the template's identifier
     * @param request the answers to hold
     * @return the template as stored
     * @throws NotFoundException when there is no such template, or another user owns it
     */
    public NotificationTemplateResponse replaceFulfilments(
        String ownerId, String id, TemplateFulfilmentsRequest request) {
        NotificationTemplate template = load(ownerId, id);
        template.setFulfilments(new ArrayList<>(request.fulfilments()));
        return NotificationTemplateResponse.from(notificationTemplateRepository.save(template));
    }

    /**
     * Saves a template, so Manage templates lists it. Saving a template already saved leaves it
     * saved. The first save records when it was saved, which orders the list; saving again keeps
     * that time.
     *
     * @param ownerId the user who owns the template
     * @param id the template's identifier
     * @return the template as stored
     * @throws NotFoundException when there is no such template, or another user owns it
     */
    public NotificationTemplateResponse save(String ownerId, String id) {
        NotificationTemplate template = load(ownerId, id);
        template.setStatus(TemplateStatus.SAVED);
        if (template.getSaved() == null) {
            template.setSaved(Instant.now());
        }
        return NotificationTemplateResponse.from(notificationTemplateRepository.save(template));
    }

    private NotificationTemplate load(String ownerId, String id) {
        return notificationTemplateRepository.findByIdAndOwnerId(id, ownerId)
            .orElseThrow(() -> new NotFoundException("Template not found"));
    }
}
