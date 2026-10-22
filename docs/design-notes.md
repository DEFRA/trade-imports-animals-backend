# Design notes

Why the code is shaped the way it is. Code states what it does; this file holds the reasons.

## Dates and timestamps in MongoDB

- Moments (`created`, `updated`, `submittedAt`, `expireAt`, the audit `timestamp`) are `Instant`. An `Instant` decodes without a zone, so a change of JVM zone between the writer and the reader cannot shift it.
- Calendar dates (`arrivalDate`, `exitDate`) are `LocalDate`, stored by `LocalDateStringConverters` as `YYYY-MM-DD` strings. MongoDB has no date-only type, and Spring Data's built-in converter stores a `LocalDate` as a moment in the JVM zone, so under `Europe/London` in summer `2026-07-21` was stored as `2026-07-20T23:00:00Z`.
- `YYYY-MM-DD` strings sort in date order, so sorts and range queries still work.
- The string-to-`LocalDate` reader runs only where the target property is a `LocalDate`. Date-shaped strings in `String` fields, `fulfilments` and outbox `data` stay strings.
- BSON dates hold milliseconds, so sub-millisecond digits on a stored `Instant` are dropped.
- `PersistedTimestampZoneIT` asserts the raw BSON, not a repository round trip, because a round trip decodes in the zone that encoded it and hides any drift. It pins the JVM to `Europe/London`, because under UTC there is no drift to detect.
- The GBN-AG event emits `issueDateTime` with every sub-second digit, and emits an arrival date as UTC midnight of that date, never the JVM zone's midnight.

## Request validation

- A date-only field rejects a value carrying a time or an offset with 400, rather than keeping the date part. Jackson's `LocalDateDeserializer` is lenient by default; `StrictLocalDateModule` makes it strict.
- `GlobalExceptionHandler` answers an unreadable body with 400 and a generic message. The parser message quotes the submitted value and names internal types, so it is logged, never returned.
- `GlobalExceptionHandler` handles `RuntimeException`, not every exception, so Spring still answers its own framework exceptions, such as 404 for an unknown path.

## Notification lifecycle

- `submitNotification` saves at the caller's `concurrencyToken`, so `@Version` refuses the save atomically when the notification changed after the caller read it.
- `amendNotification` stores deep copies of the notification and its fulfilments as the pre-amend snapshot, so `cancelAmendNotification` restores exactly what was submitted. A shared reference at any depth would let a later edit change the snapshot.
- Only a submission sets a new `submittedAt`. Cancelling an amendment returns to SUBMITTED and keeps the original `submittedAt`.
- Soft delete of an already DELETED notification returns it unchanged, so a repeated call after a lost response is harmless.

## Automatic expiry outside prod

Two independent safeguards keep expiry out of prod; both must fail before prod loses a notification:

- Stamping: `expireAt` is set at creation only when `NOTIFICATION_TTL_DAYS` is configured and `ENVIRONMENT` is not `prod`.
- Sweeping: `NotificationExpirySweeper` exists only when `NOTIFICATION_TTL_SWEEP_ENABLED=true`, with no `matchIfMissing`.

`expireAt` counts from `created`, so later edits do not extend it. Notifications created without `expireAt` are never swept. The sweep polls hourly and deletes a bounded batch, because expiry is measured in days. It writes no audit record, because it is not a user action.

## Accompanying documents and cdp-uploader

The browser uploads straight to cdp-uploader, so the uploaded bytes never arrive here; the only time this service handles them is a download, which streams from S3. The frontend opens the upload session; the calls that reach here record that session, look it up again when the browser returns, claim its document once the user's answers are in, and receive the scan result.

- A session and a document are separate records in separate collections. The frontend opens a session every time it paints the upload page — a trader who loads it, reloads it or comes back to it opens several — so writing a document then would leave a notification holding a document per page view, with nothing to tell them apart from the real ones. A document is written by whichever of the claim and the scan callback arrives first, both of which mean a file genuinely exists.
- Registering a session carries both the `uploadId` and the `correlationId`, because the callback body echoes back only the `correlationId` it was given at initiate time, and the callback URL is fixed inside the `/initiate` request — before cdp-uploader has assigned an `uploadId`. The caller that opened the session is the only party holding both, so it has to hand them over.
- Sessions expire 24 hours after they are created, which is how the ones nobody uploaded through are cleared out. Documents do not expire.
- The browser's return to the upload page looks a session up by notification and handle together, so the handle it carries cannot reach a session another notification opened. The scan callback has only the handle, which is why the unauthenticated-callback note below matters.
- A document the scan callback created is unclaimed and has no details until the frontend claims it. `claimed` separates a document the user attached from an upload they walked away from, and only claimed documents reach a notification's events. Without it, a clean scan on an abandoned upload would ship on the next GBN-AG event as a document nobody attached. `claimed` was added without a backfill because the service was not live at the time, so the records a migration would have touched did not exist — that is a fact about the moment, not a rule about the pattern: a field added to an outbox filter after launch needs its own backfill.
- Claiming is repeatable: it replaces the details, so a repeated claim cannot produce a second document.
- The scan-result callback is unauthenticated: anyone who can reach it with a known `correlationId` can post a result. The `correlationId` is minted by the frontend and travels in a browser-visible return URL (`?upload=<uuid>`), so the handle is user-supplied and has to be treated as such. Add a shared secret or HMAC check when cdp-uploader supports callback authentication.
- The callback path variable is always the literal `pending` set when the session is opened. The document is found by `metadata.correlationId` in the payload.
- cdp-uploader owns the callback body's shape and echoes back every field the browser submitted, including ones this service has no use for, so the body is read leniently: the `form` section keeps only the values carrying a `fileId`, and a file entry tolerates fields this service does not know. An object-shaped `form` value without a `fileId` is not a file entry; it is dropped with a warning naming the field, because a document saved with no files can still finish as `COMPLETE` and support needs something to search on.
- A download streams the file through this service's response. If S3 fails mid-stream after the headers are sent, the client gets a 200 with a truncated body; a failure before the first byte is still a 500.

## Outbound proxy

On CDP all egress except the database goes through the platform proxy named by `HTTP_PROXY`. `ProxyConfig` sets the JVM proxy properties and the default `ProxySelector`, so every HTTP client uses it; the proxy routes internal `*.cdp-int.defra.cloud` URLs itself. A malformed `HTTP_PROXY` is logged with any credentials replaced by `***`.

`RestClientConfig` keeps a single `RestClient.Builder` bean that nothing in this service injects. It is retained because it is the only place the CDP trust store and the `x-cdp-request-id` interceptor are wired together, and because deleting it would orphan `TraceIdPropagationInterceptor` and its test as well. The `RestTemplate` beans beside it went when the cdp-uploader client did: nothing injected them, and `RestTemplate` is not for new code. The next outbound HTTP client injects the builder; it should not re-wire the trust store itself.

## Outbox writes

- `appendEvent` reads the claimed, scan-complete documents itself rather than taking them from its caller, so the documents an event lists are the ones committed with it. The callers in `NotificationService.writeWithOutbox` are `@Transactional` and hold the outbox lock across the append, so a claim or a scan result landing alongside a submit either joins that event or waits for the next one.
- `SUBMISSION_EVENT_WIRE_VALUES` is derived from `OutboxEventType.SUBMISSION_EVENTS` rather than listed again, so the set the version count queries on cannot drift from the set the enum declares.
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
