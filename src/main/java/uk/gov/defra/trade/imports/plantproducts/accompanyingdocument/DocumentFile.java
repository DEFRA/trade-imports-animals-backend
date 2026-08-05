package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentFile {

    private String fileId;
    private String filename;
}
