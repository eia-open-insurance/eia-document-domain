# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Document legal holds and disposition: `POST /api/v1/documents/{documentId}/hold`
  with `{"reason":"..."}`, `POST /api/v1/documents/{documentId}/hold/release`,
  and `POST /api/v1/documents/{documentId}/dispose`. Each route forwards
  `X-EIA-Tenant` and the `tenantId` query to the expert and preserves expert
  400/404/409 failures as `application/problem+json`. The `DocumentDto`
  projection adds `holdReason`, `heldAt`, `statusBeforeHold`, and `disposedAt`.
- Document association links: `GET|POST /api/v1/documents/{documentId}/links` and
  `DELETE /api/v1/documents/{documentId}/links/{linkId}`, proxied to the expert
  with tenant forwarding and `application/problem+json` preservation for
  expert 400/404/409 failures.
- Optional `linkedType` + `linkedId` filter on `GET /api/v1/documents`.
- Expert-backed search for `q`, including `linkedReference` matching, with the
  local tenant-list projection as the fallback when the expert search route is
  absent.
- Document content proxy: `PUT /api/v1/documents/{documentId}/content` for raw
  bytes with `Content-Type` and `X-File-Name` (forwarded unchanged so the
  expert can URL-decode it), `GET /api/v1/documents/{documentId}/content` for
  the byte stream with `Content-Type`, `Content-Disposition`, and
  `X-Content-SHA256`, and
  `GET /api/v1/documents/{documentId}/content/metadata`. Tenant forwarding and
  expert 400/404/413 problem preservation match the link proxy. Upload bodies
  are limited to 10 MiB, including an early filter rejection for oversized
  `Content-Length` and chunked bodies, returned as a `FILE_TOO_LARGE` 413
  problem.
- Document content versions: `GET /api/v1/documents/{documentId}/content/versions`
  returns the retained revision history and
  `GET /api/v1/documents/{documentId}/content/versions/{version}` streams the
  bytes of one revision with the expert's `Content-Type`,
  `Content-Disposition`, and `X-Content-SHA256` headers forwarded unchanged.
  Both routes forward `X-EIA-Tenant` and the `tenantId` query to the expert and
  preserve expert 400/404 problems as `application/problem+json`. The content
  metadata projection (`upload` and `GET .../content/metadata`) now carries
  `currentVersion`.
- Document content tombstones: `DELETE /api/v1/documents/{documentId}/content`
  forwards `X-EIA-Tenant` and the `tenantId` query to the expert and answers
  `204 No Content`; the expert keeps the file metadata while clearing the
  bytes. The content metadata projection adds nullable `deletedAt` and the
  derived `deleted` boolean. Expert problems are preserved unchanged: missing
  content is 404 (`CONTENT_NOT_FOUND`), a legal hold or a repeated delete is
  409 (`DOCUMENT_ON_LEGAL_HOLD` / `CONTENT_ALREADY_DELETED`), and a download of
  tombstoned content is 410 (`CONTENT_DELETED`).
