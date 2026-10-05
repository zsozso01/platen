# Security policy

## Reporting a vulnerability

Please report security problems **privately** through GitHub:
<https://github.com/zsozso01/platen/security/advisories/new>

Do not open a public issue for anything exploitable. You will get an answer within a few days. Fixes are
released as soon as they are verified, and reporters are credited unless they prefer otherwise.

## What matters most here

Platen reads data from devices it does not control (printers, and anything on the local network that
claims to be one) and from documents the user opens. The areas where a bug is most likely to be a security
bug:

* **Parsers** of printer data: the IPP decoder, HTTP framing, PJL and Device ID parsers, mDNS records.
  They must never crash, hang or allocate unboundedly on malformed input.
* **Command injection** into printer languages: values that end up in PJL or IPP (job names, file names,
  setting values) must not be able to smuggle extra commands.
* **TLS handling** for IPPS: certificate trust and pinning.
* **Anything that sends data off the device** other than to the printer the user chose. There should be none.

## Supported versions

The project is pre-release; only the latest `main` is supported.
