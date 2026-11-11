package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record Clause(String identifier, String content, String urlId) {

    static final String INTERNAL_MARKET_REASON = "internalMarket";

    static final String GBN_AG_PURPOSES = "https://refdata.tbc.defra.gov.uk/gbn-ag-purposes";
    static final String GBN_AG_INTERNAL_MARKET_PURPOSES =
        "https://refdata.tbc.defra.gov.uk/gbn-ag-internal-market-purposes";
    static final String GBN_AG_GOODS_CERTIFIED_AS = "https://refdata.tbc.defra.gov.uk/gbn-ag-goods-certified-as";

    private static final String PURPOSE = "PURPOSE";
    private static final String INTERNAL_MARKET_PURPOSE = "INTERNAL_MARKET_PURPOSE";
    private static final String GOODS_CERTIFIED_AS = "GOODS_CERTIFIED_AS";

    static Clause purpose(String reasonForImport, String referenceNumber) {
        return coded(PURPOSE, reasonForImport, purposeCode(reasonForImport), GBN_AG_PURPOSES, referenceNumber);
    }

    static Clause internalMarketPurpose(String purposeInInternalMarket, String referenceNumber) {
        return coded(INTERNAL_MARKET_PURPOSE, purposeInInternalMarket,
            internalMarketPurposeCode(purposeInInternalMarket), GBN_AG_INTERNAL_MARKET_PURPOSES, referenceNumber);
    }

    static Clause goodsCertifiedAs(String certifiedFor, String referenceNumber) {
        return coded(GOODS_CERTIFIED_AS, certifiedFor, goodsCertifiedAsCode(certifiedFor),
            GBN_AG_GOODS_CERTIFIED_AS, referenceNumber);
    }

    private static Clause coded(
        String identifier, String storedKey, String code, String urlId, String referenceNumber) {
        if (storedKey == null || storedKey.isBlank()) {
            return new Clause(identifier, null, null);
        }
        if (code == null) {
            log.warn("Notification {} sends {} value {} unchanged, as its list has no code for it",
                referenceNumber, identifier, storedKey);
            return new Clause(identifier, storedKey, urlId);
        }
        return new Clause(identifier, code, urlId);
    }

    private static String purposeCode(String reasonForImport) {
        return switch (reasonForImport) {
            case INTERNAL_MARKET_REASON -> "INTERNAL_MARKET";
            case "transhipmentOrOnwardTravel" -> "TRANSHIPMENT_OR_ONWARD_TRAVEL";
            case "transit" -> "TRANSIT";
            case "reEntry" -> "RE_ENTRY";
            case "temporaryAdmissionHorses" -> "TEMPORARY_ADMISSION";
            case null, default -> null;
        };
    }

    private static String internalMarketPurposeCode(String purposeInInternalMarket) {
        return switch (purposeInInternalMarket) {
            case "transfer-of-ownership-sale-gift" -> "TRANSFER_OF_OWNERSHIP_SALE_GIFT";
            case "transfer-of-ownership-rescue" -> "TRANSFER_OF_OWNERSHIP_RESCUE";
            case "breeding" -> "BREEDING";
            case "research" -> "RESEARCH";
            case "racing-competition-show-or-training" -> "RACING_COMPETITION_SHOW_OR_TRAINING";
            case "approved-premises-or-body" -> "APPROVED_PREMISES_OR_BODY";
            case "companion-animal-not-for-resale-or-rehoming" -> "COMPANION_ANIMAL_NOT_FOR_RESALE_OR_REHOMING";
            case "production" -> "PRODUCTION";
            case "slaughter" -> "SLAUGHTER";
            case "fattening" -> "FATTENING";
            case "restocking" -> "RESTOCKING";
            case null, default -> null;
        };
    }

    private static String goodsCertifiedAsCode(String certifiedFor) {
        return switch (certifiedFor) {
            case "further-keeping" -> "FURTHER_KEEPING";
            case "slaughter" -> "SLAUGHTER";
            case "confined-establishment" -> "CONFINED_ESTABLISHMENT";
            case "germinal-products" -> "GERMINAL_PRODUCTS";
            case "registered-equine-animal" -> "REGISTERED_EQUIDAE";
            case "travelling-circus-animal-act" -> "TRAVELLING_CIRCUS_ANIMAL_ACT";
            case "exhibition" -> "EXHIBITION";
            case "event-or-activity-near-borders" -> "EVENT_OR_ACTIVITY_NEAR_BORDERS";
            case "release-into-the-wild" -> "RELEASE_INTO_THE_WILD";
            case "dispatch-centre" -> "DISPATCH_CENTRE";
            case "relaying-area-purification-centre" -> "RELAYING_AREA_PURIFICATION_CENTRE";
            case "ornamental-aquaculture-establishment" -> "ORNAMENTAL_AQUACULTURE_ESTABLISHMENT";
            case "technical-use" -> "TECHNICAL_USE";
            case "quarantine-or-similar-establishment" -> "QUARANTINE_OR_SIMILAR_ESTABLISHMENT";
            case "live-aquatic-animals-for-human-consumption" -> "LIVE_AQUATIC_ANIMALS_FOR_HUMAN_CONSUMPTION";
            case "other" -> "OTHER";
            case null, default -> null;
        };
    }
}
