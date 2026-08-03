package uk.gov.defra.trade.imports.animals.fulfilment;

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
public class FulfilmentService {

    private static final String CANNOT_FIND_FULFILMENT_WITH_ID =
        "Cannot find fulfilment with id: ";
    private static final int MAX_REF_RETRIES = 3;
    private static final List<FulfilmentStatus> LISTED_STATUSES = List.of(
        FulfilmentStatus.DRAFT,
        FulfilmentStatus.SUBMITTED,
        FulfilmentStatus.AMEND);

    private final FulfilmentRepository fulfilmentRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final MongoTemplate mongoTemplate;
    private final int listPageSize;

    public FulfilmentService(
        FulfilmentRepository fulfilmentRepository,
        ReferenceNumberGenerator referenceNumberGenerator,
        MongoTemplate mongoTemplate,
        @Value("${fulfilment.list.page-size:20}") int listPageSize) {
        this.fulfilmentRepository = fulfilmentRepository;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.mongoTemplate = mongoTemplate;
        this.listPageSize = listPageSize;
    }

    public Fulfilment create() {
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            Fulfilment fulfilment = Fulfilment.builder()
                .id(referenceNumberGenerator.generate())
                .fulfilment(List.of())
                .status(FulfilmentStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .build();
            try {
                Fulfilment saved = fulfilmentRepository.insert(fulfilment);
                log.info("Fulfilment created with id: {}", saved.getId());
                return saved;
            } catch (DuplicateKeyException e) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying",
                    attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    public ReplaceResult replace(String id, FulfilmentDto dto) {
        if (dto.getId() != null && !id.equals(dto.getId())) {
            throw new BadRequestException(
                "Path id and fulfilment body id must match");
        }

        Fulfilment existing = fulfilmentRepository.findById(id).orElse(null);
        boolean created = existing == null;
        Fulfilment fulfilment = created
            ? Fulfilment.builder()
                .id(id)
                .status(FulfilmentStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .build()
            : existing;

        assertWritable(fulfilment);
        fulfilment.setFulfilment(dto.getFulfilment());
        Fulfilment saved = fulfilmentRepository.save(fulfilment);
        log.info("{} fulfilment {}", created ? "Created" : "Replaced", id);
        return new ReplaceResult(saved, created);
    }

    public Fulfilment findById(String id) {
        return fulfilmentRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_FULFILMENT_WITH_ID + id));
    }

    @Transactional
    public Fulfilment copy(String id, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key must not be blank");
        }

        Fulfilment existingCopy = findCopy(idempotencyKey);
        if (existingCopy != null) {
            log.info("Returning existing fulfilment copy {} for idempotency key",
                existingCopy.getId());
            return existingCopy;
        }

        Fulfilment source = findById(id);
        if (!isCopyable(source.getStatus())) {
            throw new BadRequestException(
                "Cannot copy fulfilment with status: " + source.getStatus());
        }

        List<Document> copiedContent = source.getFulfilment() == null
            ? List.of()
            : new ArrayList<>(source.getFulfilment());
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            Fulfilment copy = Fulfilment.builder()
                .id(referenceNumberGenerator.generate())
                .fulfilment(copiedContent)
                .status(FulfilmentStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .copyIdempotencyKey(idempotencyKey)
                .build();
            try {
                Fulfilment saved = fulfilmentRepository.insert(copy);
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
    public Fulfilment submit(String id, String correlationId, Actor actor) {
        Fulfilment fulfilment = findById(id);
        if (!isDraftOrAmend(fulfilment.getStatus())) {
            throw new BadRequestException(
                "Cannot submit fulfilment with status: " + fulfilment.getStatus());
        }
        fulfilment.setStatus(FulfilmentStatus.SUBMITTED);
        fulfilment.setSubmittedFulfilment(null);
        fulfilment.setSubmittedAt(LocalDateTime.now());
        log.info("Submitted fulfilment {}", id);
        return fulfilmentRepository.save(fulfilment);
    }

    @Transactional
    public Fulfilment amend(String id, String correlationId, Actor actor) {
        Fulfilment fulfilment = findById(id);
        if (fulfilment.getStatus() != FulfilmentStatus.SUBMITTED) {
            throw new BadRequestException(
                "Cannot amend fulfilment with status: " + fulfilment.getStatus());
        }
        fulfilment.setSubmittedFulfilment(new ArrayList<>(fulfilment.getFulfilment()));
        fulfilment.setStatus(FulfilmentStatus.AMEND);
        fulfilment.setSubmittedAt(null);
        log.info("Amended fulfilment {}", id);
        return fulfilmentRepository.save(fulfilment);
    }

    @Transactional
    public Fulfilment cancelAmend(String id) {
        Fulfilment fulfilment = findById(id);
        if (fulfilment.getStatus() != FulfilmentStatus.AMEND) {
            throw new BadRequestException(
                "Cannot cancel amendment for fulfilment with status: "
                    + fulfilment.getStatus());
        }
        if (fulfilment.getSubmittedFulfilment() == null) {
            throw new BadRequestException(
                "Cannot cancel amendment: no submitted snapshot stored for fulfilment");
        }

        fulfilment.setFulfilment(new ArrayList<>(fulfilment.getSubmittedFulfilment()));
        fulfilment.setSubmittedFulfilment(null);
        fulfilment.setStatus(FulfilmentStatus.SUBMITTED);
        fulfilment.setSubmittedAt(LocalDateTime.now());
        log.info("Cancelled amendment for fulfilment {}", id);
        return fulfilmentRepository.save(fulfilment);
    }

    @Transactional
    public Fulfilment softDelete(String id) {
        Fulfilment fulfilment = findById(id);
        if (fulfilment.getStatus() == FulfilmentStatus.DELETED) {
            return fulfilment;
        }
        if (!isCopyable(fulfilment.getStatus())) {
            throw new BadRequestException(
                "Cannot delete fulfilment with status: " + fulfilment.getStatus());
        }

        fulfilment.setStatus(FulfilmentStatus.DELETED);
        log.info("Soft deleted fulfilment {}", id);
        return fulfilmentRepository.save(fulfilment);
    }

    public FulfilmentPageResponse findAll(int page, String sort) {
        return findAll(page, sort, null);
    }

    public FulfilmentPageResponse findAll(
        int page, String sort, String referenceNumber) {
        int normalisedPage = Math.max(page, 1);
        String trimmedReference = StringUtils.trimToNull(referenceNumber);
        Criteria statusCriteria = listedCriteria(trimmedReference);
        long totalElements = mongoTemplate.count(
            Query.query(statusCriteria), Fulfilment.class);
        long offset = (normalisedPage - 1L) * listPageSize;
        Sort rowSort = FulfilmentSort.toSort(sort)
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
        List<FulfilmentPageResponse.Item> items = mongoTemplate.aggregate(
            aggregation,
            Fulfilment.class,
            FulfilmentPageResponse.Item.class).getMappedResults();

        return FulfilmentPageResponse.from(
            normalisedPage, listPageSize, totalElements, items);
    }

    private Criteria listedCriteria(String referenceNumber) {
        Criteria criteria = Criteria.where("status").in(LISTED_STATUSES);
        if (referenceNumber != null) {
            criteria.and("_id").is(referenceNumber);
        }
        return criteria;
    }

    private AggregationOperation enrichedRowProjection() {
        return Aggregation.project()
            .and("status").as("status")
            .and("createdAt").as("createdAt")
            .and("submittedAt").as("submittedAt")
            .and("_id").as("reference")
            .and("notification.commodity").as("commodityDisplay")
            .and("notification.origin.countryCode").as("originCountryCode")
            .and("notification.transport.arrivalDate").as("arrivalDate")
            .and("notification.consignor.name").as("consignorName")
            .and("notification.consignee.name").as("consigneeName");
    }

    private void assertWritable(Fulfilment fulfilment) {
        if (!isDraftOrAmend(fulfilment.getStatus())) {
            throw new BadRequestException(
                "Journey \"" + fulfilment.getId() + "\" has status "
                    + fulfilment.getStatus() + " — writes blocked");
        }
    }

    // DRAFT and AMEND are the only editable and submittable lifecycle states.
    private boolean isDraftOrAmend(FulfilmentStatus status) {
        return status == FulfilmentStatus.DRAFT || status == FulfilmentStatus.AMEND;
    }

    private boolean isCopyable(FulfilmentStatus status) {
        return status == FulfilmentStatus.DRAFT
            || status == FulfilmentStatus.SUBMITTED
            || status == FulfilmentStatus.AMEND;
    }

    private Fulfilment findCopy(String idempotencyKey) {
        return fulfilmentRepository
            .findByCopyIdempotencyKey(idempotencyKey)
            .orElse(null);
    }

    public record ReplaceResult(Fulfilment fulfilment, boolean created) {

    }
}
