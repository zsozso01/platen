# 0004. No telemetry, no account, no ads, no off-device traffic

**Status:** accepted · **Date:** 2026-10-05

## Context

Printing handles private documents. The project's priorities include "no ads, no account, no telemetry
by default". Most printing apps do the opposite.

## Decision

* No account, ads, analytics, crash reporting or tracking SDKs in any release of this project.
* The app talks only to printers the user picked or that sit on their local network. It contacts no server
  operated by the project.
* Diagnostics are manual and local: the user exports a scrubbed text report and decides who sees it.
* "Telemetry by default" is not merely off: there is no telemetry code to switch on. If opt-in diagnostics
  are ever added, they are documented in `docs/PRIVACY.md` before they ship.
* Dependencies are chosen with this in mind: nothing that phones home, no Google Play Services requirement
  (which also keeps the app F-Droid-eligible).

## Consequences

* We learn about problems only from voluntary reports, so the diagnostic export must be good and printer
  compatibility reports are first-class (an issue template exists for them).
* Some conveniences (cloud print, remote job history) are impossible by design.
* The promise applies to this project's releases. Apache-2.0 allows forks to differ (see
  [0001](0001-apache-2-license.md)).
