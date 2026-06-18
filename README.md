# trade-imports-animals-backend

Core delivery Java Spring Boot backend template.

* [Running the local stack](#running-the-local-stack)
* [MongoDB](#mongodb)
* [Inspect MongoDB](#inspect-mongodb)
* [Testing](#testing)
* [Running](#running)
* [Dependabot](#dependabot)


### Running the local stack

The full local environment (MongoDB, Floci, Redis, `cdp-uploader`, the
stubs, and every trade-imports-animals service including this one) is the
workspace stack in
[DEFRA/trade-imports-animals-workspace](https://github.com/DEFRA/trade-imports-animals-workspace):

```bash
# from the workspace root
./scripts/stack/run-stack.sh        # full stack from published images
./scripts/stack/run-stack.sh -d     # built from local source under repos/
./scripts/stack/run-stack.sh -e backend   # everything except this service (run it from your IDE)
./scripts/stack/stop-stack.sh       # tear down and wipe volumes
```

To run only the infrastructure this service needs rather than the full stack,
limit it to the `database` and `infrastructure` profiles (MongoDB, Floci,
Redis):

```bash
./scripts/stack/run-stack.sh --profile database --profile infrastructure
```

After editing Java source in `-d` mode, pick the change up with
`./scripts/stack/bounce-backend.sh`.

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
