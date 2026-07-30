package uk.gov.defra.trade.imports.animals.notification;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import uk.gov.defra.trade.imports.animals.outbox.Actor;

@Value
@Builder
@Jacksonized
public class ActorRequest {

    String id;
    String source;
    String userType;
    String displayName;
    String organisationId;
    String onBehalfOfOrganisationId;

    public Actor toActor() {
        return Actor.builder()
            .id(id)
            .source(source)
            .userType(userType)
            .displayName(displayName)
            .organisationId(organisationId)
            .onBehalfOfOrganisationId(onBehalfOfOrganisationId)
            .build();
    }
}
