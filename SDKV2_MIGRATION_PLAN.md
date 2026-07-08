# AWS SDK v1 → v2 Migration Strategy

## Context

Synapse Repository Services depends on **AWS SDK v1** (`com.amazonaws`, BOM `1.12.768`) across ~272 Java files and 13+ AWS services. AWS SDK v1 is end-of-life (maintenance mode, no new features, eventual security-patch cutoff), so we must move to **AWS SDK v2** (`software.amazon.awssdk`, BOM `2.29.35`).

The good news: **both SDKs are already on the classpath**, and v2 is already in production for newer features (grid/agent: Bedrock, API Gateway, OpenSearch Serverless, and partial S3/SQS/SNS/STS clients in `ManagerConfiguration.java`). A v2 credentials chain already exists (`lib/stackConfiguration/.../aws/v2/AwsCredentialsProviderV2.java`). This means the migration can proceed **incrementally, one service at a time**, with v1 and v2 coexisting until the final cleanup — no big-bang cutover.

A single PR is unreviewable. This plan slices the work into a sequence of **per-service vertical slices**, ordered low-risk → high-risk, ending with full removal of SDK v1.

**Decisions confirmed with the requester:**
- **End state:** full removal of v1 (drop the v1 BOM and all `AwsClientFactory` v1 methods, including long-tail services).
- **Slicing:** per-service vertical slices — each PR migrates one service end-to-end (client construction + all caller model types) across whatever modules it touches. No service left half-migrated.
- **S3 facade:** expose v2 types and migrate callers (no long-lived translation layer). Achieved incrementally via additive dual-method facade → batch caller migration → flip (details below).

## Guiding Principles (apply to every PR)

1. **One service per PR family.** v2 model classes (request/response objects, exceptions) are service-specific, so a service is the natural unit. A file that uses two services (e.g. a worker using both S3 and SQS) is touched once per service PR — acceptable and keeps each PR coherent.
2. **Every PR fully builds and is green.** Each PR migrates a service's client construction in `AwsClientFactory`/Spring config plus all of that service's caller model types and tests. Run unit tests for every touched module; run the relevant IT tests.
3. **Coexistence until cleanup.** Do not remove the v1 BOM or v1 factory methods until the final PR. v1 and v2 imports may legitimately coexist in the codebase (and occasionally in one file) mid-migration.
4. **Migrate behind existing seams where they exist.** Facades like `SynapseS3Client` and the worker `MessageDrivenRunner` framework are the leverage points — migrate the seam, then the leaves.
5. **Establish the v2 client-construction pattern once.** Mirror the existing v2 bean style in `ManagerConfiguration.java` and the v2 credentials chain (`AwsCredentialsProviderV2`) for every new v2 client.

## Phase 0 — Foundation (1 PR)

**Goal:** make v2 client construction a first-class, centralized capability and satisfy the root-pom dependency-management rule.

- Move all AWS SDK **v2** service artifacts into the root `pom.xml` `<dependencyManagement>` (per CLAUDE.md, versions live in root; sub-modules declare without `<version>`). Currently v2 artifacts are scattered in `services/repository-managers/pom.xml` and `lib/stackConfiguration/pom.xml`. Add the v2 artifacts the migration will need (s3, sqs, sns, ses, cloudwatch, athena, glue, kms, sts, firehose, sfn, apigatewayv2, ssm, appconfigdata, cloudfront, plus `s3-transfer-manager` and HTTP client artifacts).
- Create a v2 client factory — **`AwsClientFactoryV2`** in `lib/stackConfiguration/.../aws/` — mirroring `AwsClientFactory`, building each v2 client with `AwsCredentialsProviderV2` and `software.amazon.awssdk.regions.Region.US_EAST_1`. This is the single construction point every later PR draws from. (Existing one-off v2 beans in `ManagerConfiguration.java` can be refactored to call it opportunistically.)
- No behavior change; no service migrated yet. This PR is pure infrastructure and is easy to review.

Key files: root `pom.xml`, `lib/stackConfiguration/pom.xml`, `services/repository-managers/pom.xml`, new `AwsClientFactoryV2.java`.

## Phase 1 — Long-tail / leaf services (one small PR each)

These have thin or no caller-side model leakage and few files. Each is a self-contained PR that also establishes the per-service pattern for reviewers. Order roughly by ascending footprint:

| PR | Service | ~Files (main+test) | Notes |
|----|---------|--------------------|-------|
| 1 | **CloudFront** | 1 | leaf |
| 2 | **AppConfig** | 1 | factory-only-ish |
| 3 | **SSM / Parameter Store** | 1 | factory-only-ish |
| 4 | **Step Functions** | 2 | workers + factory |
| 5 | **API Gateway v2** | 2 | v2 client already partly used; consolidate |
| 6 | **STS** | 4 | v2 `StsClient` already exists in `ManagerConfiguration`; finish + wire `managers-spb.xml` |
| 7 | **SNS** | 5 | `RepositoryMessagePublisherImpl` + `aws-topic-publisher.spb.xml`; v2 `SnsClient` already created |
| 8 | **KMS** | 5 | mostly in `stackConfiguration` |
| 9 | **Kinesis Firehose** | 5 | `lib/logging` + `kinesis-spb.xml` |

Per PR: add the v2 method to `AwsClientFactoryV2`, migrate callers' model types + exception handling, update the Spring XML/Java bean, remove the v1 factory method for that service, migrate tests.

## Phase 2 — Mid-size services (one PR family each)

- **PR family: Athena + Glue** (~33 files, tightly coupled via query execution). Migrate behind the existing facade `AthenaSupport`/`AthenaSupportImpl` (`lib/jdomodels/.../athena/`) and the `JdoModelsConfig.amazonAthenaClient()` / `amazonGlueClient()` beans. Athena and Glue share the data-warehouse query path, so do them together. May split into 2 PRs (Glue metadata first, then Athena query execution) if review size demands.
- **PR family: SES** (~25 files, almost all in `repository-managers`). Migrate behind the email manager/DAO seam and `amazonSESClient` in `managers-spb.xml`. v2 SES request/response builders + exception types.
- **PR family: CloudWatch** (~26 files: `repository-managers`, `lib/logging`, `repository`). Migrate the metrics-publishing path and `cloudwatch-spb.xml`. Watch for the `MetricDatum`/`PutMetricDataRequest` builder changes.

## Phase 3 — S3 (PR family S0–S7)

S3 leaks v1 model types **deep into ~33 main caller files** (worst: `ObjectMetadata`×33, `S3Object`×23, `GetObjectRequest`×19, `PutObjectRequest`×14). A v1-stable facade does **not** insulate callers, so we expose v2 types and migrate callers — done incrementally via an additive dual-method facade so each PR stays reviewable.

- **S0 — Region-aware v2 S3 provider (~3–5 files).** Re-express `SynapseS3ClientImpl`'s region resolution (bucket→region cache, `headBucket`-based lookup) against v2 `Region` + a v2 `S3Client` map, behind a new internal interface. **This is the highest-risk slice — pin region behavior with tests.** (See gotcha: v1 `s3.model.Region` `US_Standard == null` quirk vs v2 `regions.Region`.)
- **S1 — Additive v2 facade methods (~2 files).** Add v2-typed sibling methods to `SynapseS3Client` (e.g. `getObjectV2`, `putObjectV2`) backed by the v2 client; keep all v1 methods. Callers migrate opportunistically; nothing breaks.
- **S2 — Leaf readers/streamers (~5–6 files + tests).** `AmazonS3UtilityImpl`, `LogDAOImpl`, `S3BucketObjectReader`, wiki/table readers. `S3Object`/`S3ObjectInputStream` → v2 `ResponseInputStream<GetObjectResponse>` (verify abort-on-partial-read semantics).
- **S3p — Presigned URLs (~3 files + tests).** v2 presigning is a separate `S3Presigner` object — isolate it. `FileHandleManagerImpl` (presign portion), `UploadContentToS3DAOImpl`.
- **S4 — Multipart upload DAO (~2–3 files + tests).** `lib/lib-upload/.../S3MultipartUploadDAOImpl.java` — densest type surface (`InitiateMultipartUpload*`, `CopyPartRequest`, `UploadPartRequest`, `PartETag`, `StorageClass`).
- **S5 — TransferManager users (~4 files + tests).** v2 has **no in-core TransferManager**; use `software.amazon.awssdk:s3-transfer-manager` (CRT-based, async API) — confirm artifact added in Phase 0. `FileHandleManagerImpl` (download/copy), `GridManagerImpl`, `JdoModelsConfig`, `AwsClientFactory.createTransferManager()`. Highest-risk after S0.
- **S6 — CORS / bucket-config / tagging / restore (~4–6 files + tests).** `FileHandleArchivalManagerImpl`, CORS config, `PublishToS3`, table managers.
- **S7 — Flip & cleanup (~3 files).** Remove v1 methods from facade, rename `*V2` → canonical, drop the v1 client map and `createAmazonS3Client()`.

S3 gotchas to encode in tests: region resolution; streaming abort; exceptions (`AmazonS3Exception.getStatusCode()` → `S3Exception.statusCode()`); `putObject(InputStream, ObjectMetadata)` requires known content length in v2 (`RequestBody.fromInputStream(stream, len)`).

## Phase 4 — SQS (PR family Q0–Q6)

SQS touches ~100 files but the leak is **wide in file count, shallow in API surface**, and the worker framework already insulates most workers:
- 20 `ChangeMessageDrivenRunner` + 7 `BatchChangeMessageDrivenRunner` workers operate on the Synapse `ChangeMessage` POJO — **already fully insulated** from v1 `Message`.
- 12 `TypedMessageDrivenRunner` workers receive a (vestigial, unused) raw `Message` param alongside a converted POJO.
- Only ~8 raw `MessageDrivenRunner` consumers genuinely touch `Message`, and only via `getBody()`/`getReceiptHandle()`/`getMessageAttributes()`/`getAttributes()`.

So: **migrate the framework first, then mop up the few raw consumers.**

- **Q0 — v2 SQS client + wiring (~5 files).** Add `createSqsClientV2` to `AwsClientFactoryV2`; flip constructor injection in `WorkersInfraConfig`, `ChangeMessageWorkersConfig`, `AsyncJobWorkersConfig`, `MessageDrivenWorkersConfig`. Do **not** change `MessageDrivenRunner` yet.
- **Q1 — `ConcurrentManagerImpl` internals (~2 files + tests).** Convert receive/delete/visibility/`getQueueUrl` to v2; temporarily translate v2 `Message` → v1 `Message` at the `MessageDrivenRunner` boundary so leaf workers don't change yet. Handle `getAmazonSQSClient()` return type + its callers.
- **Q2 — `PollingMessageReceiverImpl` + `MessageQueueImpl` + `QueueCleaner` + older `MessageDrivenWorkerStack` (~4–5 files + tests).** Same boundary translation.
- **Q3 — Flip the framework `Message` type (keystone, ~6–8 files + tests).** Migrate `MessageDrivenRunner`, `TypedMessageDrivenRunner`(+adapters), `ChangeMessageBatchProcessor`, `MessageUtils` to v2 `Message`; remove the temporary translators. The 27 `ChangeMessage` workers are unaffected. Opportunistically drop the unused `Message` param from `TypedMessageDrivenRunner.run`. `MessageUtils` test factories (`createMessage`/`buildMessage`) ripple into many worker tests — budget review time.
- **Q4 / Q5 — Raw `MessageDrivenRunner` leaf workers (~2 PRs, ~4–5 files each + tests).** `getBody()`→`body()`, `getReceiptHandle()`→`receiptHandle()`, etc. Split by domain package.
- **Q6 — Remaining direct `AmazonSQS` users in `repository-managers` (~10 main + 10 test).** Queue-name/URL helpers + IT scaffolding; batch by package.

SQS gotchas: keep clients **synchronous** (no `SqsAsyncClient`); v2 `Message`/requests are **immutable builders** (`.builder()...build()` vs v1 `withX()` setters); `getQueueUrl` returns `GetQueueUrlResponse.queueUrl()`; `MessageSystemAttributeName` enum + `messageSystemAttributeNames(...)` shape changed; broaden the worker retry catch from v1 `AmazonServiceException` → v2 `AwsServiceException`/`SdkException` (this maps unrecoverable AWS errors to `RecoverableMessageException`).

> Note: SNS (`RepositoryMessagePublisher`) is **not** SQS — it's migrated separately in Phase 1 PR 7.

## Phase 5 — Final cleanup (1 PR)

- Remove the v1 BOM (`com.amazonaws:aws-java-sdk-bom`) and all per-service `aws-java-sdk-*` declarations from root `pom.xml` and sub-module poms.
- Delete `AwsClientFactory` (v1) and any remaining v1-only helpers (`SynapseAWSCredentialsProviderChain`, the `ProfileCredentialsProviderV2V1Adapter` bridge, v1 credential providers) once nothing references them.
- Verify zero residual usage: `grep -rl "com.amazonaws" --include="*.java"` returns nothing.
- Final full reactor build + IT suite.

## Critical Files (seams referenced repeatedly)

- `lib/stackConfiguration/src/main/java/org/sagebionetworks/aws/AwsClientFactory.java` — v1 central factory (replaced by new `AwsClientFactoryV2`)
- `lib/stackConfiguration/src/main/java/org/sagebionetworks/aws/v2/AwsCredentialsProviderV2.java` — v2 credentials chain (reuse everywhere)
- `services/repository-managers/src/main/java/org/sagebionetworks/repo/manager/config/ManagerConfiguration.java` — existing v2 bean pattern to mirror
- `lib/stackConfiguration/.../aws/SynapseS3Client.java` + `SynapseS3ClientImpl.java` — S3 facade
- `lib/lib-worker-common/.../aws/message/MessageDrivenRunner.java` + `PollingMessageReceiverImpl.java` — SQS framework seam
- `lib/lib-worker/.../concurrent/ConcurrentManagerImpl.java`, `.../sqs/MessageUtils.java` — preferred worker stack + message converter
- `lib/jdomodels/.../athena/AthenaSupportImpl.java` + `JdoModelsConfig.java` — Athena/Glue seam
- Spring bean configs: `managers-spb.xml`, `aws-topic-publisher.spb.xml`, `upload-dao.spb.xml`, `cloudwatch-spb.xml`, `kinesis-spb.xml`

## Verification (per PR and at milestones)

> **Local vs. remote test execution.** IT tests (`IT*` in `integration-test/`) and any autowired tests that require a local MySQL database (e.g. `*AutowiredTest`) **cannot be run locally** in this environment. They run automatically when the branch is pushed to the remote. Locally we verify with compilation + plain unit tests (`*Test` with mocks) only; the database-backed and integration suites are validated by CI on push.

1. **Per PR (local):** `mvn clean install -pl <touched-modules> -DskipTests` to confirm compilation, then `mvn test -pl <module>` for the mock-based unit tests of the migrated classes (`-Dtest=<Class>` to target). Confirm no new `com.amazonaws.*` import appears for an already-migrated service.
2. **Per PR (remote/CI on push):** the relevant `IT*` tests for the migrated service path (e.g. file/S3 upload-download round-trip, message/queue flow, email send, Athena query) plus any autowired-DB tests run on the remote branch — do not block local progress on them.
3. **Mid-migration build (local):** `mvn clean install -DskipTests` over the full reactor stays green with both SDKs present.
4. **Migration-safety check** (for any DBO-adjacent change): `MigratableTableDAOImplAutowireTest.testAllMigrationTypesRegistered()` — runs on push (autowired/DB-backed); this migration is not expected to touch DBO registration.
5. **Final gate:** `grep -rl "com.amazonaws" --include="*.java"` returns empty; full reactor build green locally; complete IT suite green on the remote branch; v1 BOM absent from dependency tree (`mvn dependency:tree | grep amazonaws` empty).

## Suggested PR count

~Phase 0 (1) + Phase 1 (9) + Phase 2 (3–5) + S3 (8) + SQS (7) + cleanup (1) ≈ **29–31 PRs**, each scoped to one service or one S3/SQS sub-slice, ordered low-risk → high-risk so the team builds confidence and a repeatable pattern before tackling S3 and the worker framework.
