# Roadmap

Platen is built in public and is **pre-alpha**. This is the plan, in the order it will be built. Dates are
deliberately absent: the order matters more than the calendar. Priorities, as set out in the
[README](../README.md): reliability first, then broad standards-based compatibility, then PC-class
settings, then UX.

## Milestones

| | Milestone | State |
|---|---|---|
| M0 | **Foundation**: repo, Gradle build, CI, docs, community files | ✅ done |
| M1 | **Protocol libraries**: IEEE 1284 Device ID, PJL, IPP (codec, HTTP framing, client, typed attributes), fake printer for tests | ✅ done |
| M2 | **Wi-Fi MVP** (target: HP DeskJet 3700): mDNS discovery, capability probe, PDF → raster, PWG Raster encoder, IPP `Print-Job`, job status, cancel, manual duplex, settings UI | 🚧 next |
| M3 | **USB MVP** (target: HP LaserJet E42540): USB host transport, interface probing, Device ID, IPP-over-USB, PJL + native PDF fallback, permission and error UX | 📋 |
| M4 | **PC-class settings**: page ranges, reverse, odd/even, pages per sheet, booklet, scaling (actual / fit / fill / custom), margins, saved presets, print preview | 📋 |
| M5 | **System integration**: share/"open with" target, Android `PrintService` provider that can open Platen's own UI | 📋 |
| M6 | **Release**: accessibility pass, translations, F-Droid metadata, Play listing, signed reproducible builds | 📋 |

## After M6 (not promised, in rough order)

* More page languages: Apple Raster (URF), PCLm, PostScript, PCL 5, PCL XL
* More job protocols and transports: raw TCP 9100 + PJL, LPD, IPPS with trust-on-first-use certificates
* Wi-Fi Direct onboarding
* A small data-driven quirks table for printers that are known to misbehave
* Per-printer vendor extensions only where a vendor *documents* them

## What would change the order

* A real printer behaving differently from what the documentation says. Hardware results always win.
* Contributors. M1 was designed so the protocol libraries are self-contained and easy to extend.

## Explicitly out of scope for now

* Printers that need a proprietary, undocumented driver and expose no standard language
* Cloud printing, accounts, analytics, ads
* Scanning (a different problem; sibling projects such as sane-airscan exist)
