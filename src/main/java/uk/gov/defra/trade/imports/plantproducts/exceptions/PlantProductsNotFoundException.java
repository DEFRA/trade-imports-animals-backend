package uk.gov.defra.trade.imports.plantproducts.exceptions;

public class PlantProductsNotFoundException extends RuntimeException {

    public PlantProductsNotFoundException(String message) {
        super(message);
    }
}
