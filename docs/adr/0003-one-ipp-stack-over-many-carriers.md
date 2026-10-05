# 0003. One IPP stack over TCP, TLS and USB; IPP-first capability detection

**Status:** accepted · **Date:** 2026-10-05

## Context

Most printers sold in the last decade speak IPP: it is how AirPrint, Mopria and IPP Everywhere work, and
IPP Everywhere *requires* PWG Raster and recommends IPP-over-USB. IPP also lets a client *ask* what the
printer supports (`Get-Printer-Attributes`), which is exactly what the "no huge printer database"
requirement needs. Research on the first two target printers:

* The HP DeskJet 3700 accepts no PDF at all but takes PWG Raster over IPP. Its capabilities (simplex,
  portrait, 300 dpi, fixed margins) are all discoverable via IPP.
* The HP LaserJet E42540 shows signs of IPP-over-USB interfaces next to the classic printer interface.
* Apple's CUPS maintainer states that almost all AirPrint printers with USB support IPP-over-USB, except
  some large MFPs; low-end HP inkjets are a counter-example.

## Decision

* Implement IPP once (`protocol:ipp`), over a generic input/output stream pair, and reuse it for
  Wi-Fi/LAN, TLS and USB.
* Detect capabilities in this order: IPP attributes, then PJL `INFO VARIABLES/CONFIG`, then the IEEE 1284
  `CMD:` hint, then a small quirks table that may only work around faults (never add capabilities).
* PJL-over-raw is the **fallback** for printers without IPP, not the main path.
* Probe USB interfaces at run time. Accept `7/1/4` and HP's `255/9/1` as IPP-over-USB; never hard-code one.

## Consequences

* One well-tested client serves most printers on every connection type.
* A fake IPP printer (`testing:fake-printer`) is enough to test most of the stack without hardware.
* IPP-over-USB has its own rules (persistent connection, read each response fully, per-device
  initialisation quirks). They are handled in the USB transport and flagged in `IppHttpTransport`.
* Printers with neither IPP nor a documented language are unsupported until someone contributes a backend.
