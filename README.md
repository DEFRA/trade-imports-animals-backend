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

- `BACKEND_BASE_URL` — externally reachable base URL for this service
  (typical local value: `http://host.docker.internal:8085`)
- `FRONTEND_BASE_URL` — externally reachable base URL for the frontend
  (typical local value: `http://localhost:3000`)

Example `.env`:

```
BACKEND_BASE_URL=http://host.docker.internal:8085
FRONTEND_BASE_URL=http://localhost:3000
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

Run the application with the `local` Spring profile, which supplies development
defaults for `BACKEND_BASE_URL` and `FRONTEND_BASE_URL`:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or equivalently:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Without the `local` profile the application reads `application.yml`, which has
no defaults for `BACKEND_BASE_URL` / `FRONTEND_BASE_URL` (deployed environments
must set these explicitly), so startup fails fast with a placeholder-resolution
error. To run without the profile, export those vars manually first:

```bash
export BACKEND_BASE_URL=http://host.docker.internal:8085
export FRONTEND_BASE_URL=http://localhost:3000
mvn spring-boot:run
```

### SonarCloud

Example SonarCloud configuration are available in the GitHub Action workflows.

### Dependabot

We have added an example dependabot configuration file to the repository. You can enable it by renaming
the [.github/example.dependabot.yml](.github/dependabot.yml) to `.github/dependabot.yml`


### About the licence

The Open Government Licence (OGL) was developed by the Controller of Her Majesty's Stationery Office (HMSO) to enable
information providers in the public sector to license the use and re-use of their information under a common open
licence.

It is designed to encourage use and re-use of information freely and flexibly, with only a few conditions.
