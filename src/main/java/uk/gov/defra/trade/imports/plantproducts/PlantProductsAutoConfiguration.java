package uk.gov.defra.trade.imports.plantproducts;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import uk.gov.defra.trade.imports.plantproducts.configuration.PlantProductsNotificationTtlConfig;

@AutoConfiguration
@AutoConfigureAfter(MongoRepositoriesAutoConfiguration.class)
@ComponentScan("uk.gov.defra.trade.imports.plantproducts")
@EnableMongoRepositories(basePackages = "uk.gov.defra.trade.imports.plantproducts")
@EnableConfigurationProperties(PlantProductsNotificationTtlConfig.class)
public class PlantProductsAutoConfiguration {
}
