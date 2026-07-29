package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.mongodb.core.mapping.Field;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Actor {

    @Field("id")
    String id;
    String source;
    String userType;
    String displayName;
    String organisationId;
    String onBehalfOfOrganisationId;
}
