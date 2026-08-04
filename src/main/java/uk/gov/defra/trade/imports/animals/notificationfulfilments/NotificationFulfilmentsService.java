package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.outbox.Actor;

@Service
@Slf4j
public class NotificationFulfilmentsService {

    private static final String CANNOT_FIND_FULFILMENT_WITH_ID =
        "Cannot find fulfilment with id: ";
    private static final int MAX_REF_RETRIES = 3;
    private static final String FIELD_STATUS = "status";
    private static final List<NotificationFulfilmentsStatus> LISTED_STATUSES = List.of(
        NotificationFulfilmentsStatus.DRAFT,
        NotificationFulfilmentsStatus.SUBMITTED,
        NotificationFulfilmentsStatus.AMEND);

    private final NotificationFulfilmentsRepository notificationFulfilmentsRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final MongoTemplate mongoTemplate;
    private final int listPageSize;

    public NotificationFulfilmentsService(
        NotificationFulfilmentsRepository notificationFulfilmentsRepository,
        ReferenceNumberGenerator referenceNumberGenerator,
        MongoTemplate mongoTemplate,
        @Value("${fulfilment.list.page-size:20}") int listPageSize) {
        this.notificationFulfilmentsRepository = notificationFulfilmentsRepository;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.mongoTemplate = mongoTemplate;
        this.listPageSize = listPageSize;
    }

    public NotificationFulfilments create() {
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            NotificationFulfilments fulfilment = NotificationFulfilments.builder()
                .id(referenceNumberGenerator.generate())
                .fulfilments(List.of())
                .status(NotificationFulfilmentsStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .build();
            try {
                NotificationFulfilments saved = notificationFulfilmentsRepository.insert(fulfilment);
                log.info("NotificationFulfilments created with id: {}", saved.getId());
                return saved;
            } catch (DuplicateKeyException e) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying",
                    attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    public ReplaceResult replace(String id, NotificationFulfilmentsDto dto) {
        if (dto.getId() != null && !id.equals(dto.getId())) {
            throw new BadRequestException(
                "Path id and fulfilment body id must match");
        }

        NotificationFulfilments existing = notificationFulfilmentsRepository.findById(id).orElse(null);
        boolean created = existing == null;
        NotificationFulfilments fulfilment = created
            ? NotificationFulfilments.builder()
                .id(id)
                .status(NotificationFulfilmentsStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .build()
            : existing;

        assertWritable(fulfilment);
        fulfilment.setFulfilments(dto.getFulfilments());
        NotificationFulfilments saved = notificationFulfilmentsRepository.save(fulfilment);
        log.info("{} fulfilment {}", created ? "Created" : "Replaced", id);
        return new ReplaceResult(saved, created);
    }

    public NotificationFulfilments findById(String id) {
        return notificationFulfilmentsRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_FULFILMENT_WITH_ID + id));
    }

    @Transactional
    public NotificationFulfilments copy(String id, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key must not be blank");
        }

        NotificationFulfilments existingCopy = findCopy(idempotencyKey);
        if (existingCopy != null) {
            log.info("Returning existing fulfilment copy {} for idempotency key",
                existingCopy.getId());
            return existingCopy;
        }

        NotificationFulfilments source = findById(id);
        if (!isCopyable(source.getStatus())) {
            throw new BadRequestException(
                "Cannot copy fulfilment with status: " + source.getStatus());
        }

        List<Document> copiedContent = source.getFulfilments() == null
            ? List.of()
            : new ArrayList<>(source.getFulfilments());
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            NotificationFulfilments copy = NotificationFulfilments.builder()
                .id(referenceNumberGenerator.generate())
                .fulfilments(copiedContent)
                .status(NotificationFulfilmentsStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .idempotencyKey(idempotencyKey)
                .build();
            try {
                NotificationFulfilments saved = notificationFulfilmentsRepository.insert(copy);
                log.info("Copied fulfilment {} to {}", id, saved.getId());
                return saved;
            } catch (DuplicateKeyException e) {
                existingCopy = findCopy(idempotencyKey);
                if (existingCopy != null) {
                    log.info("Returning concurrently-created fulfilment copy {}",
                        existingCopy.getId());
                    return existingCopy;
                }
                log.warn("Reference number collision on copy persistence attempt {}/{}; retrying",
                    attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    @Transactional
    public NotificationFulfilments submit(String id, String correlationId, Actor actor) {
        NotificationFulfilments fulfilment = findById(id);
        if (!isDraftOrAmend(fulfilment.getStatus())) {
            throw new BadRequestException(
                "Cannot submit fulfilment with status: " + fulfilment.getStatus());
        }
        fulfilment.setStatus(NotificationFulfilmentsStatus.SUBMITTED);
        fulfilment.setSubmittedFulfilments(null);
        fulfilment.setSubmittedAt(LocalDateTime.now());
        log.info("Submitted fulfilment {}", id);
        return notificationFulfilmentsRepository.save(fulfilment);
    }

    @Transactional
    public NotificationFulfilments amend(String id, String correlationId, Actor actor) {
        NotificationFulfilments fulfilment = findById(id);
        if (fulfilment.getStatus() != NotificationFulfilmentsStatus.SUBMITTED) {
            throw new BadRequestException(
                "Cannot amend fulfilment with status: " + fulfilment.getStatus());
        }
        fulfilment.setSubmittedFulfilments(new ArrayList<>(fulfilment.getFulfilments()));
        fulfilment.setStatus(NotificationFulfilmentsStatus.AMEND);
        fulfilment.setSubmittedAt(null);
        log.info("Amended fulfilment {}", id);
        return notificationFulfilmentsRepository.save(fulfilment);
    }

    @Transactional
    public NotificationFulfilments cancelAmend(String id) {
        NotificationFulfilments fulfilment = findById(id);
        if (fulfilment.getStatus() != NotificationFulfilmentsStatus.AMEND) {
            throw new BadRequestException(
                "Cannot cancel amendment for fulfilment with status: "
                    + fulfilment.getStatus());
        }
        if (fulfilment.getSubmittedFulfilments() == null) {
            throw new BadRequestException(
                "Cannot cancel amendment: no submitted snapshot stored for fulfilment");
        }

        fulfilment.setFulfilments(new ArrayList<>(fulfilment.getSubmittedFulfilments()));
        fulfilment.setSubmittedFulfilments(null);
        fulfilment.setStatus(NotificationFulfilmentsStatus.SUBMITTED);
        fulfilment.setSubmittedAt(LocalDateTime.now());
        log.info("Cancelled amendment for fulfilment {}", id);
        return notificationFulfilmentsRepository.save(fulfilment);
    }

    @Transactional
    public NotificationFulfilments softDelete(String id) {
        NotificationFulfilments fulfilment = findById(id);
        if (fulfilment.getStatus() == NotificationFulfilmentsStatus.DELETED) {
            return fulfilment;
        }
        if (!isCopyable(fulfilment.getStatus())) {
            throw new BadRequestException(
                "Cannot delete fulfilment with status: " + fulfilment.getStatus());
        }

        fulfilment.setStatus(NotificationFulfilmentsStatus.DELETED);
        log.info("Soft deleted fulfilment {}", id);
        return notificationFulfilmentsRepository.save(fulfilment);
    }

    public NotificationFulfilmentsPageResponse findAll(int page, String sort) {
        return findAll(page, sort, null);
    }

    public NotificationFulfilmentsPageResponse findAll(
        int page, String sort, String referenceNumber) {
        int normalisedPage = Math.max(page, 1);
        String trimmedReference = StringUtils.trimToNull(referenceNumber);
        Criteria statusCriteria = listedCriteria(trimmedReference);
        long totalElements = mongoTemplate.count(
            Query.query(statusCriteria), NotificationFulfilments.class);
        long offset = (normalisedPage - 1L) * listPageSize;
        Sort rowSort = NotificationFulfilmentsSort.toSort(sort)
            .and(Sort.by(Sort.Direction.ASC, "_id"));

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(listedCriteria(trimmedReference)),
            Aggregation.lookup(
                mongoTemplate.getCollectionName(Notification.class),
                "_id",
                "referenceNumber",
                "notification"),
            Aggregation.unwind("notification", true),
            enrichedRowProjection(),
            Aggregation.sort(rowSort),
            Aggregation.skip(offset),
            Aggregation.limit(listPageSize));
        List<NotificationFulfilmentsPageResponse.Item> items = mongoTemplate.aggregate(
            aggregation,
            NotificationFulfilments.class,
            NotificationFulfilmentsPageResponse.Item.class).getMappedResults();

        return NotificationFulfilmentsPageResponse.from(
            normalisedPage, listPageSize, totalElements, items);
    }

    private Criteria listedCriteria(String referenceNumber) {
        Criteria criteria = Criteria.where(FIELD_STATUS).in(LISTED_STATUSES);
        if (referenceNumber != null) {
            criteria.and("_id").is(referenceNumber);
        }
        return criteria;
    }

    private AggregationOperation enrichedRowProjection() {
        return Aggregation.project()
            .and(FIELD_STATUS).as(FIELD_STATUS)
            .and("createdAt").as("createdAt")
            .and("submittedAt").as("submittedAt")
            .and("_id").as("reference")
            .and("notification.commodity").as("commodityDisplay")
            .and("notification.origin.countryCode").as("originCountryCode")
            .and("notification.transport.arrivalDate").as("arrivalDate")
            .and("notification.consignor.name").as("consignorName")
            .and("notification.consignee.name").as("consigneeName");
    }

    private void assertWritable(NotificationFulfilments fulfilment) {
        if (!isDraftOrAmend(fulfilment.getStatus())) {
            throw new BadRequestException(
                "Journey \"" + fulfilment.getId() + "\" has status "
                    + fulfilment.getStatus() + " — writes blocked");
        }
    }

    // DRAFT and AMEND are the only editable and submittable lifecycle states.
    private boolean isDraftOrAmend(NotificationFulfilmentsStatus status) {
        return status == NotificationFulfilmentsStatus.DRAFT || status == NotificationFulfilmentsStatus.AMEND;
    }

    private boolean isCopyable(NotificationFulfilmentsStatus status) {
        return status == NotificationFulfilmentsStatus.DRAFT
            || status == NotificationFulfilmentsStatus.SUBMITTED
            || status == NotificationFulfilmentsStatus.AMEND;
    }

    private NotificationFulfilments findCopy(String idempotencyKey) {
        return notificationFulfilmentsRepository
            .findByIdempotencyKey(idempotencyKey)
            .orElse(null);
    }

    public record ReplaceResult(NotificationFulfilments notificationFulfilments, boolean created) {

    }
}
