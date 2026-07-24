package uk.gov.defra.trade.imports.animals.fulfilment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

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
    private final int listPageSize;

    public FulfilmentService(
        FulfilmentRepository fulfilmentRepository,
        ReferenceNumberGenerator referenceNumberGenerator,
        @Value("${fulfilment.list.page-size:20}") int listPageSize) {
        this.fulfilmentRepository = fulfilmentRepository;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.listPageSize = listPageSize;
    }

    public Fulfilment create(Owner owner) {
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            Fulfilment fulfilment = Fulfilment.builder()
                .id(referenceNumberGenerator.generate())
                .owner(owner)
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

    public ReplaceResult replace(String id, FulfilmentDto dto, Owner owner) {
        if (dto.getId() != null && !id.equals(dto.getId())) {
            throw new BadRequestException(
                "Path id and fulfilment body id must match");
        }

        Fulfilment existing = fulfilmentRepository.findById(id).orElse(null);
        boolean created = existing == null;
        Fulfilment fulfilment = created
            ? Fulfilment.builder()
                .id(id)
                .owner(owner)
                .status(FulfilmentStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .build()
            : existing;

        assertOwner(fulfilment, owner);
        assertWritable(fulfilment);
        fulfilment.setFulfilment(dto.getFulfilment());
        Fulfilment saved = fulfilmentRepository.save(fulfilment);
        log.info("{} fulfilment {}", created ? "Created" : "Replaced", id);
        return new ReplaceResult(saved, created);
    }

    public Fulfilment findById(String id, Owner owner) {
        Fulfilment fulfilment = fulfilmentRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_FULFILMENT_WITH_ID + id));
        assertOwner(fulfilment, owner);
        return fulfilment;
    }

    @Transactional
    public Fulfilment copy(String id, Owner owner, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key must not be blank");
        }

        Fulfilment existingCopy = findCopy(owner, idempotencyKey);
        if (existingCopy != null) {
            log.info("Returning existing fulfilment copy {} for idempotency key",
                existingCopy.getId());
            return existingCopy;
        }

        Fulfilment source = findById(id, owner);
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
                .owner(owner)
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
                existingCopy = findCopy(owner, idempotencyKey);
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
    public Fulfilment submit(String id, Owner owner) {
        Fulfilment fulfilment = findById(id, owner);
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
    public Fulfilment amend(String id, Owner owner) {
        Fulfilment fulfilment = findById(id, owner);
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
    public Fulfilment cancelAmend(String id, Owner owner) {
        Fulfilment fulfilment = findById(id, owner);
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
    public Fulfilment softDelete(String id, Owner owner) {
        Fulfilment fulfilment = findById(id, owner);
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

    public FulfilmentPageResponse findAll(Owner owner, int page, String sort) {
        int normalisedPage = Math.max(page, 1);
        Page<Fulfilment> result =
            fulfilmentRepository.findAllByOwnerSubAndOwnerOrganisationAndStatusIn(
                owner.sub(),
                owner.organisation(),
                LISTED_STATUSES,
                PageRequest.of(
                    normalisedPage - 1, listPageSize, FulfilmentSort.toSort(sort)));
        return FulfilmentPageResponse.from(result);
    }

    private void assertOwner(Fulfilment fulfilment, Owner owner) {
        if (!owner.equals(fulfilment.getOwner())) {
            throw new NotFoundException(CANNOT_FIND_FULFILMENT_WITH_ID + fulfilment.getId());
        }
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

    private Fulfilment findCopy(Owner owner, String idempotencyKey) {
        return fulfilmentRepository
            .findByOwnerSubAndOwnerOrganisationAndCopyIdempotencyKey(
                owner.sub(), owner.organisation(), idempotencyKey)
            .orElse(null);
    }

    public record ReplaceResult(Fulfilment fulfilment, boolean created) {

    }
}
