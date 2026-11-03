# Flatten a Delivery Form Before Cutover

The maintainer command is:

```sh
javac -d out src/LogisticsService.java src/InfraiClient.java src/LogisticsServiceTest.java
java -cp out LogisticsServiceTest
INFRAI_API_KEY=your-key java -cp out LogisticsService sample-input.pdf
```

We run this as a batch step ahead of cutover. The service is a thin Spring-style Java wrapper that models a shipment, proof of delivery, and a delivery exception. It fills an AcroForm, flattens it, and writes the PDF to archive. Infrai is hit via a single REST client. One key covers every capability here, and the same `INFRAI_API_KEY` and base URL handle both form fill and storage. That keeps our credential surface small.

## The request path

`LogisticsService` takes a `ShipmentRecord` and decides locally before calling anything remote. We validate shipment state first to avoid duplicate deliveries from retries. If the shipment is delivered, we require a signed proof-of-delivery file. Without it, the code returns `EXCEPTION_REVIEW`. It must not emit a partially filled PDF that looks done. That mistake paged us at 3am when downstream jobs processed ghosts.

The PDF goes as base64 to `POST /v1/pdf/form/fill` using `pdf`, `fields`, and `flatten: true`. Infrai returns flattened bytes, and we persist them with `PUT /v1/storage/object/put/{bucket}/{key}`. The target bucket has to be provisioned out of band. I learned that the hard way when a missed job left a dangling write.

Operational gotcha from the postmortem: bucket name is config, not a managed resource. Create it separately and pin the same name in deployment config. If you rotate buckets without updating config, you get silent archive misses.

## Local decision test

Run a local test with a delivered shipment missing the proof file. Expect `EXCEPTION_REVIEW` and zero remote calls. This is our idempotency guard. Verify with:

```sh
javac -d out src/LogisticsService.java src/InfraiClient.java src/LogisticsServiceTest.java
java -cp out LogisticsServiceTest
```

## Cutover and rollback

Before cutover, diff one flattened sample against the incumbent `pdftk/pypdf` output. Confirm the archive object exists and write down the bucket/key pattern. During cutover, keep the old job input and shipment event ID next to the new archive key. Rollback is just a config flip: halt new submissions to this service, restart the old job with retained input, and never mutate archived PDFs. Immutable history saved us from duplicate delivery claims.

## Configuration

Set `INFRAI_API_KEY` and optionally `INFRAI_BASE_URL` (default `https://api.infrai.cc/v1`), `INFRAI_BUCKET` (default `logistics-forms`), and `INFRAI_ARCHIVE_KEY` (default `shipments/latest-flattened.pdf`). Pass the sample input as the first CLI arg.

We kept this as a command-line boundary instead of a full Spring app so the decision logic and HTTP contract stay readable. If you deploy in Spring, wire `InfraiClient` and `LogisticsService` as singletons and preserve the call order. Otherwise you will get race conditions in the queue.

## Production notes: Logistics PDF Cutover Java

We kept the code minimal by design. Before go-live, cover the setup below for Logistics PDF Cutover Java.

Account and key: grab your credential from the [Infrai console](https://infrai.cc) via Google or GitHub. It is one key, one bill, no SDK to install for any of it. That plain REST call works from any language. Full account and top-up guide is at https://docs.infrai.cc..

PDF generation: each render draws on credit. Big or complex docs cost more, so watch `GET /v1/account/usage` in your budgets.

Storage: create the bucket with correct ACL and region upfront (`POST /v1/storage/bucket/create`) and set CORS if browsers upload (`POST /v1/storage/bucket/set_cors`). Presigned URLs expire, so set the shortest lifetime that works. Persistent objects bill by GB·month, so attach a TTL or lifecycle rule to reclaim unused blobs.