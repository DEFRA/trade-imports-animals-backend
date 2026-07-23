package uk.gov.defra.trade.imports.animals.fulfilment;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;

@Service
@Slf4j
@RequiredArgsConstructor
public class FulfilmentService {

    private static final String CANNOT_FIND_FULFILMENT_WITH_ID =
        "Cannot find fulfilment with id: ";
    private static final int MAX_REF_RETRIES = 3;

    private final FulfilmentRepository fulfilmentRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;

    public Fulfilment create() {
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            Fulfilment fulfilment = Fulfilment.builder()
                .id(referenceNumberGenerator.generate())
                .fulfilment(List.of())
                .status(FulfilmentStatus.IN_PROGRESS)
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
                .status(FulfilmentStatus.IN_PROGRESS)
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
    public Fulfilment submit(String id) {
        Fulfilment fulfilment = findById(id);
        assertWritable(fulfilment);
        fulfilment.setStatus(FulfilmentStatus.SUBMITTED);
        fulfilment.setSubmittedAt(LocalDateTime.now());
        log.info("Submitted fulfilment {}", id);
        return fulfilmentRepository.save(fulfilment);
    }

    @Transactional
    public Fulfilment amend(String id) {
        Fulfilment fulfilment = findById(id);
        if (fulfilment.getStatus() != FulfilmentStatus.SUBMITTED) {
            throw new BadRequestException(
                "Cannot amend fulfilment with status: " + fulfilment.getStatus());
        }
        fulfilment.setStatus(FulfilmentStatus.IN_PROGRESS);
        fulfilment.setSubmittedAt(null);
        log.info("Amended fulfilment {}", id);
        return fulfilmentRepository.save(fulfilment);
    }

    private void assertWritable(Fulfilment fulfilment) {
        if (fulfilment.getStatus() == FulfilmentStatus.SUBMITTED) {
            throw new BadRequestException(
                "Journey \"" + fulfilment.getId() + "\" is submitted — writes blocked");
        }
    }

    public record ReplaceResult(Fulfilment fulfilment, boolean created) {

    }
}
