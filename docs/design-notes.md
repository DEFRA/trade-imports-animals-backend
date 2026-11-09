# Design notes

Why the code is shaped the way it is. Code states what it does; this file holds the reasons.

## Dates and timestamps in MongoDB

- Moments (`created`, `updated`, `submittedAt`, `expireAt`, the audit `timestamp`) are `Instant`. An `Instant` decodes without a zone, so a change of JVM zone between the writer and the reader cannot shift it.
- Calendar dates (`arrivalDate`, `exitDate`, a document's `dateOfIssue`) are `LocalDate`, stored by `LocalDateStringConverters` as `YYYY-MM-DD` strings. MongoDB has no date-only type, and Spring Data's built-in converter stores a `LocalDate` as a moment in the JVM zone, so under `Europe/London` in summer `2026-07-21` was stored as `2026-07-20T23:00:00Z`.
- `YYYY-MM-DD` strings sort in date order, so sorts and range queries still work.
- The string-to-`LocalDate` reader runs only where the target property is a `LocalDate`. Date-shaped strings in `String` fields, `fulfilments` and outbox `data` stay strings.
- BSON dates hold milliseconds, so sub-millisecond digits on a stored `Instant` are dropped.
- `write-dates-as-timestamps` is pinned to `false`, the Spring Boot default, so it cannot change by accident. Enabling it would serialise an `Instant` as a decimal epoch and a `LocalDate` as a `[year, month, day]` array instead of an ISO-8601 string, which breaks the API contract silently.
- `PersistedTimestampZoneIT` asserts the raw BSON, not a repository round trip, because a round trip decodes in the zone that encoded it and hides any drift. It pins the JVM to `Europe/London`, because under UTC there is no drift to detect.
- The GBN-AG event emits `issueDateTime` with every sub-second digit, and emits an arrival date as UTC midnight of that date, never the JVM zone's midnight.
- The OpenAPI schema derives each date field's `format` from its Java type, so `format: date` tells a caller the value is a calendar date and `format: date-time` tells it the value is a moment. `OpenApiDateFormatTest` holds the two apart by resolving the schemas through the swagger model converters springdoc uses, which needs no running server.

## Request validation

- A date-only field rejects a value carrying a time or an offset with 400, rather than keeping the date part. Jackson's `LocalDateDeserializer` is lenient by default; `StrictLocalDateModule` makes it strict.
- `GlobalExceptionHandler` answers an unreadable body with 400 and a generic message. The parser message quotes the submitted value and names internal types, so it is logged, never returned.
- `GlobalExceptionHandler` handles `RuntimeException`, not every exception, so Spring still answers its own framework exceptions, such as 404 for an unknown path.

## Notification lifecycle

- `submitNotification` saves at the caller's `concurrencyToken`, so `@Version` refuses the save atomically when the notification changed after the caller read it.
- `amendNotification` stores deep copies of the notification and its fulfilments as the pre-amend snapshot, so `cancelAmendNotification` restores exactly what was submitted. A shared reference at any depth would let a later edit change the snapshot.
- The two snapshots are independent, and the notification snapshot keeps inline party details as they stood when the amendment started. Submitting or cancelling the amendment clears both.
- `submittedAt` is the latest submission: set when a DRAFT is submitted and refreshed when an AMEND is resubmitted. Starting an amendment leaves it unchanged, and cancelling one returns to SUBMITTED and keeps it.
- `fulfilments` is an opaque payload, stored as it arrives and never interpreted here.
- Soft delete of an already DELETED notification returns it unchanged, so a repeated call after a lost response is harmless.

## Automatic expiry outside prod

Two independent safeguards keep expiry out of prod; both must fail before prod loses a notification:

- Stamping: `expireAt` is set at creation only when `NOTIFICATION_TTL_DAYS` is configured and `ENVIRONMENT` is not `prod`. The service logs once at startup whether stamping is on and, if it is off, which of the two conditions turned it off.
- Removal: `NotificationExpiryIndex` creates the Mongo TTL indexes on `expireAt` only when `NOTIFICATION_TTL_EXPIRY_ENABLED=true`, with no `matchIfMissing`, and refuses in prod.

Both default to off in `application.yml`, the values prod relies on; an environment opts in by setting them.

`expireAt` counts from `created`, so later edits do not extend it. An accompanying document takes its notification's `expireAt` when the document is first written. Once the TTL indexes exist, Mongo's TTL monitor runs every 60 seconds and removes records whose `expireAt` has passed, so a record usually goes soon after that moment. No exact time is guaranteed, and nor is a notification going in the same pass as its documents. With `NOTIFICATION_TTL_DAYS` set and the flag off, records carry an `expireAt` that nothing acts on. Removal writes no audit record and no outbox event, because it is not a user action.

Mongo never removes a record with no `expireAt`:

- A notification created while stamping was off, or before stamping existed, has none, and neither does a document of that notification.
- A document written before documents took an `expireAt` has none, even if its notification has one.
- A document first written after its notification has gone has none.

Nothing in this service clears these. An orphaned document, one with no `expireAt` whose `notificationReferenceNumber` matches no notification, stays until someone deletes it by hand.

- The indexes are created at startup rather than with `@Indexed(expireAfter)`, because `auto-index-creation` would build an annotated index in every environment, prod included.
- Mongo refuses a TTL index on a key that already carries a plain index, so `NotificationExpiryIndex` drops any plain `expireAt` index from the notification collection first; only that collection ever had one. Instances starting together can both list that index, so one already dropped by another instance counts as done rather than as a startup failure.
- Switching the flag back off does not drop the TTL indexes.

## Accompanying documents and cdp-uploader

The browser uploads straight to cdp-uploader, so the uploaded bytes never arrive here; the only time this service handles them is a download, which streams from S3. The frontend opens the upload session; the calls that reach here record that session, look it up again when the browser returns, claim its document once the user's answers are in, and receive the scan result.

- A session and a document are separate records in separate collections. The frontend opens a session every time it paints the upload page — a trader who loads it, reloads it or comes back to it opens several — so writing a document then would leave a notification holding a document per page view, with nothing to tell them apart from the real ones. A document is written by whichever of the claim and the scan callback arrives first, both of which mean a file genuinely exists. Both find the document by the session's `correlationId`, so the second to arrive adds to what the first wrote.
- A notification can hold several open sessions, because a trader may start a second document while the first is still being checked.
- Registering a session carries both the `uploadId` and the `correlationId`, because the callback body echoes back only the `correlationId` it was given at initiate time, and the callback URL is fixed inside the `/initiate` request — before cdp-uploader has assigned an `uploadId`. The caller that opened the session is the only party holding both, so it has to hand them over.
- Sessions expire 24 hours after they are created, which is how the ones nobody uploaded through are cleared out. Documents expire only as "Automatic expiry outside prod" sets out.
- The browser's return to the upload page looks a session up by notification and handle together, so the handle it carries cannot reach a session another notification opened. The scan callback has only the handle, which is why the unauthenticated-callback note below matters.
- `claimed` is the domain term for a document whose trader's details (document type, document reference and date of issue) have been supplied: `DocumentService.claimUpload` writes those details and sets it. It is the stored field name and the API field name.
- A document the scan callback created is unclaimed and has no details until the frontend claims it. `claimed` separates a document the user attached from an upload they walked away from, and only claimed documents reach a notification's events. Without it, a clean scan on an abandoned upload would ship on the next GBN-AG event as a document nobody attached. `claimed` was added without a backfill because the service was not live at the time, so the records a migration would have touched did not exist — that is a fact about the moment, not a rule about the pattern: a field added to an outbox filter after launch needs its own backfill.
- Claiming is repeatable: it replaces the details, so a repeated claim cannot produce a second document.
- Deleting a document also deletes its session, so a scan result arriving afterwards cannot bring the document back.
- A download reads a document's first file. The upload workflow gives each session one file, and nothing in this service enforces that.
- The scan-result callback is unauthenticated: anyone who can reach it with a known `correlationId` can post a result. The `correlationId` is minted by the frontend and travels in a browser-visible return URL (`?upload=<uuid>`), so the handle is user-supplied and has to be treated as such. Add a shared secret or HMAC check when cdp-uploader supports callback authentication.
- The callback path variable is always the literal `pending` set when the session is opened. The document is found by `metadata.correlationId` in the payload, so a result for one of a notification's open sessions reaches only that session's document.
- A scan result fails closed: a missing or non-zero `numberOfRejectedFiles` records the document as `REJECTED`, so a file is never accepted because cdp-uploader omitted the field.
- cdp-uploader owns the callback body's shape and echoes back every field the browser submitted, including ones this service has no use for, so the body is read leniently: the `form` section keeps only the values carrying a `fileId`, and a file entry tolerates fields this service does not know. An object-shaped `form` value without a `fileId` is not a file entry; it is dropped with a warning naming the field, because a document saved with no files can still finish as `COMPLETE` and support needs something to search on.
- A download streams the file through this service's response. If S3 fails mid-stream after the headers are sent, the client gets a 200 with a truncated body; a failure before the first byte is still a 500.

## Integration test profile

- The EMF metrics publisher is off, because tests have no CloudWatch agent and a blank `AWS_EMF_NAMESPACE` raises `InvalidNamespaceException` on every scheduler tick.
- `notification.submit.lock-at-least-for` is `0ms`, so a rapid re-submission in a test can acquire the lock.

## Local AWS credentials

`AwsConfig` uses static credentials whenever `APP_AWS_ENDPOINT_OVERRIDE` and both `app.aws.access-key-id` and `app.aws.secret-access-key` are set, and nothing checks which environment it runs in. They are for local development against a stand-in such as LocalStack; never set real credentials in them.

## Outbound proxy

On CDP all egress except the database goes through the platform proxy named by `HTTP_PROXY`. `ProxyConfig` sets the JVM proxy properties and the default `ProxySelector`, so every HTTP client uses it; the proxy routes internal `*.cdp-int.defra.cloud` URLs itself. A malformed `HTTP_PROXY` is logged with any credentials replaced by `***`.

`RestClientConfig` keeps a single `RestClient.Builder` bean that nothing in this service injects. It is retained because it is the only place the CDP trust store and the `x-cdp-request-id` interceptor are wired together, and because deleting it would orphan `TraceIdPropagationInterceptor` and its test as well. The `RestTemplate` beans beside it went when the cdp-uploader client did: nothing injected them, and `RestTemplate` is not for new code. The next outbound HTTP client injects the builder; it should not re-wire the trust store itself.

## Outbox writes

- `appendEvent` reads the claimed, scan-complete documents itself rather than taking them from its caller, so the documents an event lists are the ones committed with it. The callers in `NotificationService.writeWithOutbox` are `@Transactional` and hold the outbox lock across the append, so a claim or a scan result landing alongside a submit either joins that event or waits for the next one.
- `SUBMISSION_EVENT_WIRE_VALUES` is derived from `OutboxEventType.SUBMISSION_EVENTS` rather than listed again, so the set the version count queries on cannot drift from the set the enum declares.
- `CommitRetryingMongoTransactionManager` re-commits a transaction whose commit result is unknown, within the window it is configured with. Its unit test sizes that window in whole seconds rather than tens of milliseconds, so one scheduling stall on a loaded machine cannot exhaust the window before the first retry.
- `versionId` is absent on created, edited and deleted events because the schema mints `V1` on first submission. A notification edited before it has ever been submitted would otherwise emit `versionId: 0`, a value the schema's semantics never produce.

## GBN-AG event mapping

Each party's email and telephone map to one `definedContact`. With neither, `definedContact` is null rather than an empty list, so it is omitted like every other absent field. `personName` is never set: the address book holds an organisation or address name, already mapped to the party name, and no contact person.

Document type codes follow `schemas/codelists/gbn-ag-document-types.json` in trade-imports-schemas.

Open gaps, where the event leaves a field empty until the source data or a decision exists:

- `exchangedDocument.issuer`: where the consignment contact belongs is undecided.
- The `INTERNAL_MARKET_PURPOSE` clause has no content: the sub-purpose is not collected.
- Party `identifier` and `partyRoleCode` are not set.
- `carrier.partyTypeCode` passes the transporter type through unchanged; the `operator_activity_type` codelist mapping is unconfirmed.
- `applicableClassification` `urlId` values are not set.
- Address `countryName` is not set: the notification holds only the country code.
- The final destination has no name or address: nothing collected says which address belongs to the holding.
