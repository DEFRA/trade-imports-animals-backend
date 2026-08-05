package uk.gov.defra.trade.imports.plantproducts.exceptions;

public class PlantProductsBadRequestException extends RuntimeException {

    public PlantProductsBadRequestException(String message) {
        super(message);
    }
}
