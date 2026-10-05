# 0009. USB routes and honest job tracking

**Status:** accepted · **Date:** 2026-10-05

## Context

A USB printer can offer IPP over USB, a classic printer interface carrying a page language with PJL on
top, or both (the IPP-USB specification's own example does). Printers differ in what they say back:
a modern laser answers PJL queries and reports job events; a managed one may have PJL locked down; an old
one has a unidirectional interface that cannot be read at all. A job protocol that pretends to know more than
the printer told it either claims success that did not happen or reports failures that did not.

## Decision

1. **Route order**: IPP over USB if the printer offers it and answers (it describes the printer fully and
   reports on jobs properly), otherwise the classic interface with PJL. A claim failure or a persistent
   `503` falls back to PJL. Per-printer modes (`AUTO`, `IPP_USB`, `PJL`, `RAW`) exist for troubleshooting.
2. **PJL sends only what the printer listed.** `@PJL INFO VARIABLES` says which settings and values are legal;
   anything else is left out. Copies use `QTY` (collated job copies) when listed, else `COPIES`.
3. **Completion follows what the printer says, in this order**: a `USTATUS JOB END` report (a job that ends
   with 0 pages is a *failure*); else two consecutive "ready" status answers after the last byte; else, for a
   printer that says nothing or a write-only interface, the last byte being accepted, which means *sent*
   (as for every classic printer port) and is documented as such. A printer that talked and then went quiet,
   or whose connection dropped, is reported as *detached*, never as completed or failed.
4. **A job the printer has fully received cannot be recalled with PJL.** Cancelling before that closes the job
   on a fresh connection; after that the user is told to use the printer's cancel button.
5. **No document names, addresses or serial numbers in logs.**

## Consequences

* Several rules are assumptions until a real printer confirms them (the `PAGES=0` meaning, ready-twice
  completion, which PJL variables a PDF job honours). They are listed in the printer notes as things to test.
* The USB stack is layered so that all of this runs against simulators in plain JVM tests; Android supplies only
  a thin adapter.
