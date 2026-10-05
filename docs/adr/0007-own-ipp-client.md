# 0007. Write our own IPP client instead of depending on jipp

**Status:** accepted · **Date:** 2026-10-05

## Context

IPP is the core protocol (see [0003](0003-one-ipp-stack-over-many-carriers.md)). The most capable JVM option,
HP's `jipp` (`com.hp.jipp`, **MIT-licensed**, not Apache), has a rich attribute model and also ships PWG Raster
and PCLm writers. Another option is `de.gmuth:ipp-client` (MIT). Writing our own codec is feasible because
RFC 8010 is small; the attribute vocabulary is where the work is.

What we need that off-the-shelf clients do not give us:

* **A pluggable byte transport.** The same client must run over a TCP socket, a TLS socket and a USB
  IPP-over-USB interface. Over USB there is no connection close, every response must be drained to its
  framing end, `Host: localhost` is mandatory and `Connection: close` must not be sent. jipp ships no transport
  of its own (its sample uses `HttpURLConnection`), and HTTP stacks hide exactly the connection behaviour USB
  needs to control.
* **A decoder hardened against a hostile peer** (bounded lengths, depth and counts; fuzz-tested).
* **No Android surprises** from a third-party dependency graph, in an app that wants a small, auditable supply chain.

## Decision

Implement the IPP codec, HTTP/1.1 framing and client in `protocol:ipp` ourselves (about 1,500 lines with tests),
over a minimal `IppConnection` (input and output stream). Keep jipp's MIT-licensed PCLm writer and
attribute tables as a *reference* for later work; do not depend on it today.

## Consequences

* One implementation serves TCP, TLS and USB, and its edge cases are covered by our own tests and fuzzing.
* We own the maintenance of the attribute vocabulary. It is a typed view (`IppPrinterAttributes`) over a
  generic attribute list, so a new attribute is a one-line accessor.
* If a hard-to-write format (PCLm) is wanted later, jipp's writer is MIT and can be adapted with attribution.
* Revisit if the attribute handling outgrows a thin typed view.
