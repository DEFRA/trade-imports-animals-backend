package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Declaration {

    private Boolean agreed;
    private LocalDateTime declaredAt;
}
