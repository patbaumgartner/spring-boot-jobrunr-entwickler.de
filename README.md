# Spring Boot JobRunr Example

Companion code for the German *JavaMagazin* article on JobRunr and Spring Boot on
[entwickler.de](https://entwickler.de/).

**InvoiceRun** demonstrates a monthly invoice run with JobRunr Open Source. It imports
a fixed snapshot, calculates invoices, creates a ZIP package and delivers it to a local
HTTP portal. The portal stores the package but delays its first response. JobRunr retries
the delivery, skips the durable steps that already completed and gets the original
receipt back.

## Project Overview

- **Durable steps:** import, calculation, package creation and delivery in one job.
- **Scheduling:** immediate and delayed jobs, a monthly invoice run and daily cleanup.
- **Idempotency:** repeatable requests and portal deliveries with stable IDs and checksums.
- **Monitoring:** JobRunr dashboard with job history, step progress and logs, plus a
  separate API for business status and downloads.

The project uses Spring Boot 4.2.0, JobRunr 9.0.5, Java 25 and PostgreSQL 18.6.
All customer data is synthetic. The example does not issue real invoices; its 8 % VAT
rate is a demo value.

## Getting Started

Requirements: Java 25 and Docker.

```sh
git clone git@github.com:patbaumgartner/spring-boot-jobrunr-entwickler.de.git
cd spring-boot-jobrunr-entwickler.de
./mvnw spring-boot:run
```

Spring Boot starts PostgreSQL through Docker Compose and runs the application locally.

| Service | URL |
| --- | --- |
| InvoiceRun console | <http://localhost:8080/> |
| JobRunr dashboard | <http://localhost:8000/dashboard> |
| Portal receipts | <http://localhost:8080/api/receipts> |

Dashboard credentials: `invoicing` / `invoicing-local`.
Use the portal and these credentials only for local demos.

## Running an Invoice Run

```sh
curl -i -X POST http://localhost:8080/api/invoice-runs \
  -H 'Content-Type: application/json' \
  -d '{"snapshotId":"standard","revision":1}'
```

The API returns `202 Accepted`, a run ID and a status URL in the `Location` header and
`links.self`. Open that URL to inspect `jobState`, `packageStatus`, `deliveryStatus`,
`executedSteps` and the portal's `receipt`. Links to the HTML report, CSV and ZIP appear
when the package is ready. The console offers the same operations.

The first portal request stores the package and delays its response for five seconds.
The client times out after two seconds. JobRunr retries only the unfinished delivery:

```json
["import-snapshot", "calculate-invoices", "create-package",
 "deliver-package", "deliver-package"]
```

The portal records one receipt with two attempts. Open the job's **History → Chart** in
the JobRunr dashboard to see the retry and the three skipped steps.

Repeating a request returns the same run. To create a correction run, use
`"snapshotId":"correction"` and a higher `revision`; it changes premium support from
18 to 20 hours. The same revision with different input returns `409 Conflict`.
An optional `runAt` field accepts a future ISO-8601 instant for delayed processing.

The monthly recurring job runs on the first day at 06:00 Europe/Zurich and bills the
previous month. The daily cleanup removes files seven days after confirmed delivery.

## Persistence and Retries

PostgreSQL stores jobs, invoice runs and portal receipts. The `data/` directory stores
snapshots and generated files. Keep both to resume processing after a restart.

After a hard JVM crash, JobRunr OSS may have lost the step progress since its last save.
All four steps can then run again. The calculation and ZIP creation are repeatable, and
the portal returns the same receipt for the same transfer ID and checksum.

Saving an invoice run and creating its JobRunr job are separate writes. If the second
write fails, repeating the API request repairs the handover using the same job ID.

## Tests

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Unit tests cover calculation, rounding, validation, CSV export and the recurring billing
month. Integration tests use a real JobRunr worker with PostgreSQL via Testcontainers
and cover retries, concurrent deliveries, conflicting input and repeatable job creation.
Docker must be running for the integration tests.

## License

[Apache License 2.0](LICENSE)

---

[patbaumgartner.com](https://patbaumgartner.com) · [More talks & projects on GitHub](https://github.com/patbaumgartner)
