package uk.gov.defra.trade.imports.animals.notification;

import io.swagger.v3.oas.annotations.media.Schema;

public enum MeansOfTransport {
  @Schema(description = "Airplane")
  AIRPLANE,

  @Schema(description = "Railway")
  RAILWAY,

  @Schema(description = "Road vehicle")
  ROAD_VEHICLE,

  @Schema(description = "Vessel")
  VESSEL
}