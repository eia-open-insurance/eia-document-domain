# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Document association links: `GET|POST /api/v1/documents/{documentId}/links` and
  `DELETE /api/v1/documents/{documentId}/links/{linkId}`, proxied to the expert
  with tenant forwarding and `application/problem+json` preservation for
  expert 400/404/409 failures.
- Optional `linkedType` + `linkedId` filter on `GET /api/v1/documents`.
- Expert-backed search for `q`, including `linkedReference` matching, with the
  local tenant-list projection as the fallback when the expert search route is
  absent.
