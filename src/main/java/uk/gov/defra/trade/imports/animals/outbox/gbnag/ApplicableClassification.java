package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;

public record ApplicableClassification(
    String systemId,
    String systemName,
    CodedValue classCode,
    List<String> className
) {

    private static final String SYSTEM_ID_CN = "CN";

    static ApplicableClassification cn(String cnCode) {
        if (cnCode == null) {
            return null;
        }
        return new ApplicableClassification(SYSTEM_ID_CN, null, CodedValue.of(cnCode), null);
    }
}
