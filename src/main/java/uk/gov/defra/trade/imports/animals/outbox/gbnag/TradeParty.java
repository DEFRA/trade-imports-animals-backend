package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.ConsignmentParty;
import uk.gov.defra.trade.imports.animals.notification.Transporter;

public record TradeParty(
    String identifier,
    String urlId,
    String name,
    CodedValue partyRoleCode,
    List<CodedValue> partyTypeCode,
    TradeAddress postalAddress,
    List<DefinedContact> definedContact
) {

    private static final String OPERATOR_ACTIVITY_TYPE =
        "https://traces-codelists.ec.europa.eu/operator_activity_type";
    private static final String UK_TRANSPORTER_AUTHORISATION =
        "https://refdata.tbc.defra.gov.uk/uk_transporter_authorisation";
    static TradeParty from(ConsignmentParty party) {
        if (party == null) {
            return null;
        }
        return new TradeParty(
            null, null, party.getName(),
            null,
            null, TradeAddress.from(party.getAddress()), definedContactFrom(party));
    }

    private static List<DefinedContact> definedContactFrom(ConsignmentParty party) {
        if (party.getEmail() == null && party.getPhone() == null) {
            return null;
        }
        return List.of(new DefinedContact(null, party.getEmail(), party.getPhone()));
    }

    static TradeParty from(Transporter transporter) {
        if (transporter == null) {
            return null;
        }
        // TODO(EUDPA-274 gap): transporter type is passed through raw - confirm the operator_activity_type codelist mapping is correct. //NOSONAR
        List<CodedValue> partyTypeCode = transporter.getType() != null
            ? List.of(CodedValue.of(transporter.getType(), OPERATOR_ACTIVITY_TYPE))
            : null;
        return new TradeParty(
            transporter.getApprovalNumber(),
            transporter.getApprovalNumber() != null ? UK_TRANSPORTER_AUTHORISATION : null,
            transporter.getName(),
            null,
            partyTypeCode,
            TradeAddress.from(transporter.getAddress()),
            null);
    }
}
