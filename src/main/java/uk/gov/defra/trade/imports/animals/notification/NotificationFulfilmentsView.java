package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;

/**
 * Spring Data interface projection over the {@code notification} collection backing
 * {@code GET /notifications/{ref}/fulfilments}. Server-only fields
 * ({@code submittedFulfilmentsBaseline}, {@code expireAt}) are intentionally omitted so they
 * aren't loaded on read.
 *
 * <p>{@code submittedNotificationBaseline} <em>is</em> exposed, narrowed to its parties by
 * {@link FrozenParties}. That looks out of pattern next to the opaque {@code fulfilments}
 * blob, but the frontend rehydrates a journey through this endpoint alone ({@code GET
 * /notifications/{ref}/fulfilments}) and needs the submit freeze to render a submitted
 * notification without live address-book lookups. Only the six party fields travel — the rest
 * of the snapshot stays server-side, same as {@code submittedFulfilmentsBaseline}.
 */
public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    LocalDateTime getSubmittedAt();

    List<Document> getFulfilments();

    /**
     * The parties as they were at submit, or {@code null} for a notification that has never been
     * submitted. Present for SUBMITTED and for an in-flight AMEND; only a submitted notification
     * should be rendered from it — an amendment is meant to show live details.
     */
    FrozenParties getSubmittedNotificationBaseline();

    /**
     * The six role fields of the submitted freeze, carrying resolved details rather than a
     * reference, so a reader renders them without touching the address book.
     */
    interface FrozenParties {

        ConsignmentParty getPlaceOfOrigin();

        ConsignmentParty getConsignor();

        ConsignmentParty getConsignee();

        ConsignmentParty getImporter();

        ConsignmentParty getDestination();

        ConsignmentParty getConsignment();
    }
}
