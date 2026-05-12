package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Transporter {

    private String name;
    private Address address;
    private String approvalNumber;
    private String type;
}
