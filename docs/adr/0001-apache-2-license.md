# 0001. License the project under Apache-2.0

**Status:** accepted · **Date:** 2026-10-05

## Context

The goals are a polished app on Google Play, distribution through F-Droid and GitHub, easy contribution of
new printer backends, and protocol modules that others can reuse. The licence decides which libraries can
be bundled and what downstream users may do.

## Decision

Apache-2.0 for all code in this repository.

## Consequences

* Compatible with Google Play and F-Droid, and with the Apache/BSD/MIT libraries we want (Android's own
  `PdfRenderer`, PDFBox, PDFium).
* The patent grant in Apache-2.0 matters for a project that implements file formats and protocols.
* Protocol modules (`protocol/*`) can be reused in other apps, including closed-source ones.
* Forks may be closed-source or add ads. The project's no-ads / no-telemetry promise therefore applies to
  *this project's* releases, not to forks (see [0004](0004-no-telemetry-no-account-no-ads.md)).
* **We cannot copy code from GPL/AGPL projects** (CUPS filters, Ghostscript, MuPDF, Poppler). Studying them
  to learn a format is fine; contributors are told this in `CONTRIBUTING.md`.
* Changing the licence later would need agreement from every contributor, so it is decided before outside
  contributions arrive.

## Alternatives considered

* **GPL-3.0-or-later**: keeps forks open, but blocks proprietary reuse of the protocol libraries and
  restricts which dependencies may be bundled.
* **AGPL-3.0**: strongest copyleft; poor fit for a Play-published app and discourages contributors.
