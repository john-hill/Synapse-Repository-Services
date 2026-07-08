# AWS SDK v1 → v2 Migration — PR Checklist

Tracking checklist for the migration described in [SDKV2_MIGRATION_PLAN.md](./SDKV2_MIGRATION_PLAN.md).
Each item is one PR (or PR family). Order is low-risk → high-risk. Check off when merged.

> Local verification = compile (`mvn install -DskipTests`) + mock-based unit tests. IT and autowired-DB tests run on push to the remote branch.

## Phase 0 — Foundation
- [x] **P0** — Consolidate v2 artifacts into root `pom.xml` `<dependencyManagement>`; add `AwsClientFactoryV2` (mirrors `AwsClientFactory`, uses `AwsCredentialsProviderV2`, `Region.US_EAST_1`). No service migrated. Add `s3-transfer-manager` + HTTP client artifacts.

## Phase 1 — Long-tail / leaf services (one PR each)
- [x] **P1.1** — CloudFront
- [x] **P1.2** — AppConfig
- [x] **P1.3** — SSM / Parameter Store
- [x] **P1.4** — Step Functions
- [x] **P1.5** — API Gateway v2 (consolidate with existing v2 usage)
- [x] **P1.6** — STS (finish v2 `StsClient`, wire `managers-spb.xml`)
- [x] **P1.7** — SNS (`RepositoryMessagePublisherImpl` + `aws-topic-publisher.spb.xml`)
- [x] **P1.8** — KMS
- [x] **P1.9** — Kinesis Firehose (`lib/logging` + `kinesis-spb.xml`)

## Phase 2 — Mid-size services (one PR family each)
- [x] **P2.1** — Athena + Glue (behind `AthenaSupport`; may split Glue / Athena)
- [ ] **P2.2** — SES (email manager/DAO seam + `managers-spb.xml`)
- [ ] **P2.3** — CloudWatch (metrics path + `cloudwatch-spb.xml`)

## Phase 3 — S3 (PR family S0–S7)
- [ ] **S0** — Region-aware v2 S3 provider behind new internal interface (highest-risk; pin region behavior with tests)
- [ ] **S1** — Additive v2-typed facade methods on `SynapseS3Client` (keep v1 methods)
- [ ] **S2** — Leaf readers/streamers → `ResponseInputStream<GetObjectResponse>`
- [ ] **S3p** — Presigned URLs via `S3Presigner`
- [ ] **S4** — Multipart upload DAO (`S3MultipartUploadDAOImpl`)
- [ ] **S5** — TransferManager users → `s3-transfer-manager` (CRT/async)
- [ ] **S6** — CORS / bucket-config / tagging / restore
- [ ] **S7** — Flip & cleanup: remove v1 facade methods, rename `*V2`, drop v1 client map + `createAmazonS3Client()`

## Phase 4 — SQS (PR family Q0–Q6)
- [ ] **Q0** — v2 SQS client + flip injection in worker config (no `MessageDrivenRunner` change yet)
- [ ] **Q1** — `ConcurrentManagerImpl` internals to v2 (temp v2→v1 `Message` translation at boundary)
- [ ] **Q2** — `PollingMessageReceiverImpl` + `MessageQueueImpl` + `QueueCleaner` + older `MessageDrivenWorkerStack`
- [ ] **Q3** — Keystone: flip framework `Message` type to v2; remove temp translators; drop unused `Message` param from `TypedMessageDrivenRunner`
- [ ] **Q4** — Raw `MessageDrivenRunner` leaf workers, batch A
- [ ] **Q5** — Raw `MessageDrivenRunner` leaf workers, batch B
- [ ] **Q6** — Remaining direct `AmazonSQS` users in `repository-managers`

## Phase 5 — Final cleanup
- [ ] **P5** — Remove v1 BOM + `aws-java-sdk-*` deps; delete `AwsClientFactory` + v1-only credential helpers; verify `grep -rl "com.amazonaws" --include="*.java"` is empty; full build + IT green on remote.
