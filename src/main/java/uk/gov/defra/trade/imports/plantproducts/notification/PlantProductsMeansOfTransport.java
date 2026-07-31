package uk.gov.defra.trade.imports.plantproducts.notification;

import io.swagger.v3.oas.annotations.media.Schema;

public enum PlantProductsMeansOfTransport {
  @Schema(description = "Airplane")
  AIRPLANE,

  @Schema(description = "Railway")
  RAILWAY,

  @Schema(description = "Road vehicle")
  ROAD_VEHICLE,

  @Schema(description = "Vessel")
  VESSEL
}
