# trade-imports-animals-backend

Spring Boot service that stores animals import notifications and their
accompanying documents, and publishes their outbox events. It listens on port
8085.

* [Running the local stack](#running-the-local-stack)
* [MongoDB](#mongodb)
* [Inspect MongoDB](#inspect-mongodb)
* [Testing](#testing)
* [Running](#running)
* [Dependabot](#dependabot)
* [Design notes](docs/design-notes.md)


### Running the local stack

The full local environment (MongoDB, Floci, Redis, `cdp-uploader`, the
stubs, and every trade-imports service including this one) is the
workspace stack in
[DEFRA/trade-imports-workspace](https://github.com/DEFRA/trade-imports-workspace):

```bash
# from the workspace root
tim docker up             # full stack from published images
tim docker dev            # built from local source under repos/
tim docker up -e backend  # everything except this service (run it from your IDE)
tim docker down           # tear down and wipe volumes
```

To run only the infrastructure this service needs rather than the full stack,
limit it to the `database` and `infrastructure` profiles (MongoDB, Floci,
Redis and `cdp-uploader`):

```bash
tim docker up --profile database --profile infrastructure
```

In `-d` mode, edited Java source reloads on its own. After a `pom.xml` or
dependency change, rebuild the backend with `tim docker bounce-backend`.

#### Floci init script

This repo owns the Floci provisioning for the stack —
[compose/start-floci.sh](compose/start-floci.sh) creates the S3
buckets, SQS queues, and the quarantine-bucket S3 event notification this
service needs. It is the single canonical copy: the workspace stack stages and
runs it in its `floci-init` container (from `repos/` when present,
sparse-fetched from GitHub in CI).

### MongoDB

The workspace stack provides MongoDB via the `database` profile (see
[Running the local stack](#running-the-local-stack) above).

#### MongoDB locally

Alternatively install MongoDB locally:

- Install [MongoDB](https://www.mongodb.com/docs/manual/tutorial/#installation) on your local machine
- Start MongoDB as a single-member replica set. The service writes a
  notification and its outbox event in one transaction, and MongoDB allows
  transactions only on a replica set:
```bash
sudo mongod --replSet rs0 --dbpath ~/mongodb-cdp
mongosh --eval 'rs.initiate()'
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

Unit tests (`*Test.java`) run with Surefire:

```bash
mvn test
```

Integration tests (`*IT.java`) start the full Spring Boot application against
[Testcontainers](https://testcontainers.com/), so they need Docker. Failsafe
runs them after the unit tests, then JaCoCo checks coverage:

```bash
mvn verify
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

The SonarCloud scan is
[.github/workflows/sonarcloud.yml](.github/workflows/sonarcloud.yml). It runs
on every pull request and on every merge to `main`.

### Dependabot

[.github/dependabot.yml](.github/dependabot.yml) proposes updates to direct
Maven dependencies and GitHub Actions every Monday.


### About the licence

The Open Government Licence (OGL) was developed by the Controller of Her Majesty's Stationery Office (HMSO) to enable
information providers in the public sector to license the use and re-use of their information under a common open
licence.

It is designed to encourage use and re-use of information freely and flexibly, with only a few conditions.
