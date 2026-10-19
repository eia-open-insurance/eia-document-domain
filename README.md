# eia-document-domain

- GitHub organization: `eia-platform`
- Repository URL: https://github.com/eia-platform/eia-document-domain
- Type: Spring Boot service
- Deployable: Yes
- Owner: Document
- Runtime: Java 22 and Spring Boot
- Data store: eia_document (tx schema)
- Depends on: eia-document-expert, eia-contracts, eia-platform-libs

## Purpose

Document business rules, transaction catalog, orchestration, approvals, and APIs. Document ingestion, classification, retention, legal hold, print, e-signature, and e-discovery.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and CI.
- Contain no business logic in shared libraries or front-end remotes.

## Required checks

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Owns the business logic for this vertical.

