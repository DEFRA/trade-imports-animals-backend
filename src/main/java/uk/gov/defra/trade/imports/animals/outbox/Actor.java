package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Actor {

    String id;
    String source;
    String userType;
    String displayName;
    String organisationId;
    String onBehalfOfOrganisationId;
}
