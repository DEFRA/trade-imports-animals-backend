package uk.gov.defra.trade.imports.animals.integration;

import static org.testcontainers.utility.DockerImageName.parse;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.RemoteDockerImage;

/**
 * An OauthMockServer instance based on OAuth Docker image.
 */
class OAuthMockServerContainer extends GenericContainer<OAuthMockServerContainer> {
    /**
     * Default constructor.
     * Constructs the GenericContainer for the MockServer based on a mock OAuth Docker image.
     * Also does a little config based on a json input file.
     */
    public OAuthMockServerContainer() {
        super(new RemoteDockerImage(parse("ghcr.io/navikt/mock-oauth2-server:2.1.10")));
        try {
            final Path path = Paths.get(IntegrationBase.class.getResource("/integration/oauth2-mock-server.json").toURI());
            this.withExposedPorts(8080);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}
