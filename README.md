# eia-document-domain

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-document-domain
- Type: Spring Boot service
- Deployable: Yes
- Owner: Document
- Runtime: Java 22 and Spring Boot
- Data store: eia_document (tx schema)
- Depends on: eia-document-expert, eia-contracts, eia-platform/libs

## Purpose

Document business rules, transaction catalog, orchestration, approvals, and APIs. Document ingestion, classification, retention, legal hold, print, e-signature, and e-discovery.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Local run

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat build
.\gradlew.bat bootRun
```

- Port: `8096`.
- Requires `X-EIA-Tenant` (UUID) on every request.
- Calls the expert at `http://localhost:8095` (`eia.document.expert-url`).
- Public API: `GET|POST /api/v1/documents`, `GET /api/v1/documents/{id}`, `POST /api/v1/documents/{id}/status`.
- Document links: `GET|POST /api/v1/documents/{documentId}/links`, `DELETE /api/v1/documents/{documentId}/links/{linkId}`.
- Document content proxy: `PUT /api/v1/documents/{documentId}/content` (raw bytes with `Content-Type` and `X-File-Name`, forwarded unchanged so the expert can URL-decode it), `GET /api/v1/documents/{documentId}/content` (bytes with `Content-Type`, `Content-Disposition`, and `X-Content-SHA256`), `GET /api/v1/documents/{documentId}/content/metadata` (the metadata projection, including `currentVersion`), `GET /api/v1/documents/{documentId}/content/versions` (the retained revision history), and `GET /api/v1/documents/{documentId}/content/versions/{version}` (the bytes of one retained revision with the same representation headers). Uploads are capped at 10 MiB (10,485,760 bytes) and oversized bodies return an `application/problem+json` 413 (`FILE_TOO_LARGE`).
- The document list accepts the optional `linkedType` + `linkedId` pair. Search forwards `q` to the expert so a linked reference can match; when the expert search route is unavailable it falls back to the local tenant list projection.
- Link, filter, and content failures keep the expert's 400/404/413 status and problem body (`application/problem+json`).

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Owns the business logic for this vertical.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
