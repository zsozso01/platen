# 0002. Separate job protocol from transport

**Status:** accepted · **Date:** 2026-10-05

## Context

The initial sketch was `Document → Rendering/Conversion → Printer Language Backend → Transport → Printer`.
Researching the first two target printers showed that "transport" was hiding two different things:

* **How the job is described**: IPP sends settings as attributes; PJL sends them as `@PJL SET` lines before
  the page data; raw printing sends none.
* **How bytes move**: TCP, TLS, USB bulk endpoints.

They vary independently. IPP runs over TCP, TLS *and* a USB interface (IPP-over-USB, class 7/1/4, plus an
HP-specific 255/9/1). PJL runs over raw TCP 9100 *and* a plain USB printer interface. The HP LaserJet
E42540 needs IPP-over-USB or PJL-over-USB; the HP DeskJet 3700 needs IPP over TCP.

## Decision

The pipeline has five stages: Document → Renderer → **Backend** (page language) → **Job protocol**
(IPP / PJL / raw) → **Transport** (TCP / TLS / USB) → Printer. Backends, job protocols, transports and
discovery providers are separate extension points.

## Consequences

* IPP-over-USB is the IPP job protocol with a USB transport: no second IPP implementation.
* A *route* is a (backend, job protocol, transport) triple chosen by the planner from detected
  capabilities.
* Slightly more abstraction than a three-layer design, paid back the first time a protocol meets a new
  transport.
