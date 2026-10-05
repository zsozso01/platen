# Contributing to Platen

Thank you for wanting to help. Platen is an open-source Android printing app with PC-class control, and it
gets better with every printer someone tries.

## The quickest ways to help

1. **Test a printer.** Install a build, print something, and file a
   [printer compatibility report](https://github.com/zsozso01/platen/issues/new?template=printer_report.yml)
   (working or not). Real dumps are worth more than code.
2. **Fix or extend a protocol library.** `protocol/*` modules are pure Kotlin with no Android dependency
   and the tightest feedback loop (`./gradlew :protocol:ipp:test`).
3. **Add support for a new page language, job protocol, transport or discovery method.** See
   *Extension points* in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): you implement one interface and
   register it.
4. **Improve the docs**, especially [docs/printers/](docs/printers/).

## Ground rules

These follow from the project's principles and are not negotiable:

* **No ads, no accounts, no telemetry, no analytics, no tracking SDKs.** A change that adds any of
  these will not be merged.
* **No network traffic except to a printer the user chose.**
* **Standards first.** Prefer IPP, PWG and documented printer languages. Reverse-engineered or
  vendor-specific support is welcome only where the vendor's behaviour is documented or independently
  verifiable, and must be isolated in its own module.
* **Detect, don't hard-code.** Ask the printer what it can do. A table of known-bad printers is
  acceptable for working around faults; a table that *adds* capabilities is not.
* **Licensing.** Code is Apache-2.0. Do not copy code from GPL/AGPL projects (CUPS filters, Ghostscript,
  MuPDF, Poppler, and similar) into the repository, and do not add dependencies with incompatible licences
  without discussing it first. Studying such code to understand a *format* is fine; transcribing it is not.

## Development setup

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Short version: JDK 21, Android SDK platform 36,
`./gradlew build`.

## Making a change

1. Open an issue first for anything bigger than a small fix, so effort is not wasted.
2. Branch from `main`. Keep changes focused; unrelated cleanups go in separate PRs.
3. Add tests. Parsers need byte-exact tests **and** malformed-input tests.
4. Run `./gradlew build -Pplaten.warningsAsErrors=true` (this is what CI runs).
5. Write commit messages that say *why*. Use the imperative: "Reject oversized chunk sizes".
6. Open a pull request using the template. Say what you tested on real hardware, or say that you did not.

## Captured printer data

Real captures (IPP attribute dumps, USB descriptors, Device IDs) are very valuable, but they contain
identifying information. Before committing or posting one, remove serial numbers, host names, MAC
addresses, IP addresses and anything under `printer-uuid`. The in-app diagnostic export does this for you.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By taking part you agree to it.

## Licence of contributions

By contributing you agree that your contribution is licensed under the Apache License 2.0, the licence of
this project.
