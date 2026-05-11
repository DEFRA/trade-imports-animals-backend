# trade-imports-animals-backend

Core delivery Java Spring Boot backend template.

* [MongoDB](#mongodb)
* [Inspect MongoDB](#inspect-mongodb)
* [Testing](#testing)
* [Running](#running)
* [Dependabot](#dependabot)


### Docker Compose

A Docker Compose template is in [compose.yml](compose.yml).

A local environment with:

- Localstack for AWS services (S3, SQS)
- Redis
- MongoDB
- `cdp-uploader` — virus-scanning upload service used by accompanying-documents
- This service.

```bash
docker compose --profile services up --build -d
```

A more extensive setup is available in [github.com/DEFRA/cdp-local-environment](https://github.com/DEFRA/cdp-local-environment)

#### Required environment variables

The compose file deliberately has no defaults for the following variables —
they must be set in every environment (e.g. via a `.env` file or shell export)
or `docker compose` will surface a missing-var warning and the service will
fail fast at startup:

- `TRADE_IMPORTS_ANIMALS_BACKEND_BASE_URL` — externally reachable base URL for this service
  (typical local value: `http://host.docker.internal:8085`). Used to construct the cdp-uploader
  scan-result callback URL.

Example `.env`:

```
TRADE_IMPORTS_ANIMALS_BACKEND_BASE_URL=http://host.docker.internal:8085
```

### MongoDB

#### MongoDB via Docker

Run infrastructure services (MongoDB, Localstack, Redis):

```bash
docker compose --profile infra up -d
```

#### MongoDB locally

Alternatively install MongoDB locally:

- Install [MongoDB](https://www.mongodb.com/docs/manual/tutorial/#installation) on your local machine
- Start MongoDB:
```bash
sudo mongod --dbpath ~/mongodb-cdp
```

#### MongoDB in CDP environments

In CDP environments a MongoDB instance is already set up
and the credentials exposed as environment variables.


### Inspect MongoDB

To inspect the Database and Collections locally:
```bash
mongosh
```

You can use the CDP Terminal to access the environments' MongoDB.

### Testing

Run the tests with:

Tests run by running a full Spring Boot application backed by [Testcontainers](https://testcontainers.com/).
Tests do not use mocking of any sort and read and write from the containerized database.

```bash
mvn test
```

### Running

Run the application with the `local` Spring profile, which supplies a development
default for `TRADE_IMPORTS_ANIMALS_BACKEND_BASE_URL`:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or equivalently:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Without the `local` profile the application reads `application.yml`, which resolves
`TRADE_IMPORTS_ANIMALS_BACKEND_BASE_URL` to an empty string if unset (deployed
environments must set it explicitly). `CdpConfig` enforces `@NotBlank` on it, so
startup fails fast with a binding/validation error
(`Property: cdp.backend.baseUrl`, `Reason: must not be blank`). To run without the
profile, export the var manually first:

```bash
export TRADE_IMPORTS_ANIMALS_BACKEND_BASE_URL=http://host.docker.internal:8085
mvn spring-boot:run
```

### SonarCloud

Example SonarCloud configuration are available in the GitHub Action workflows.

### Dependabot

We have added a dependabot configuration file to the repository at
[.github/dependabot.yml](.github/dependabot.yml).


### About the licence

The Open Government Licence (OGL) was developed by the Controller of Her Majesty's Stationery Office (HMSO) to enable
information providers in the public sector to license the use and re-use of their information under a common open
licence.

It is designed to encourage use and re-use of information freely and flexibly, with only a few conditions.
