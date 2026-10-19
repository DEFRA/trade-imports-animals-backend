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

- The scan-result callback is unauthenticated: anyone who can reach it with a known `correlationId` can post a result. Add a shared secret or HMAC check when cdp-uploader supports callback authentication.
- The callback path variable is always the literal `pending` set at initiate time. The document is found by `metadata.correlationId` in the payload.
- A download streams from S3. If S3 fails mid-stream after the headers are sent, the client gets a 200 with a truncated body; a failure before the first byte is still a 500.
- `DocumentInitiateProductionModeIT` runs cdp-uploader with `NODE_ENV=production`, the mode deployed environments use, where `redirect` must be relative and `callback` must be on `cdp-int.defra.cloud`. `DocumentControllerIT` runs it in development mode so the mock virus scanner can start, so without this test the production-only rules would go untested.

## Outbound proxy

On CDP all egress except the database goes through the platform proxy named by `HTTP_PROXY`. `ProxyConfig` sets the JVM proxy properties and the default `ProxySelector`, so every HTTP client uses it; the proxy routes internal `*.cdp-int.defra.cloud` URLs itself. A malformed `HTTP_PROXY` is logged with any credentials replaced by `***`.

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
