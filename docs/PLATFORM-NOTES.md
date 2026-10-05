# Android platform notes

Facts about Android that shape the design, collected while researching the app. Sources are primary
(AOSP, Linux kernel and USB-IF documents, CUPS, `ipp-usb`, RFCs) unless noted. Things not verified on
real hardware are marked **unverified**; test them on a device before relying on them.

## USB

* Every USB **alternate setting is its own `UsbInterface`**. Claim the interface, then call `setInterface` with
  the alternate you want. Endpoints differ per alternate setting, so read them from the selected one.
* Printer class is `7/1/protocol`: 1 unidirectional, 2 bidirectional, 3 IEEE 1284.4, **4 IPP-over-USB**.
  IPP-over-USB needs **at least two** such interfaces and is *often not alternate 0*. HP also uses a
  non-standard `255/9/1` (seen in `ipp-usb`, in no spec).
* Plan: IPP-over-USB if two or more interfaces offer protocol 4; otherwise legacy protocol 2, then 1.
* `GET_DEVICE_ID`: `bmRequestType 0xA1`, `bRequest 0`, `wValue` configuration index,
  `wIndex = (interface << 8) | alternate`. The reply starts with a big-endian 2-byte length that *includes
  itself*; some printers send little-endian. It works **without claiming** the interface.
* `SOFT_RESET`: `0x23` first, then `0x21` (class request 2). Android has no public `clearHalt` or `resetDevice`;
  the recovery ladder is soft reset, re-select the alternate, reopen the device, ask the user to power-cycle.
* **Permission**: the `requestPermission` `PendingIntent` must be explicit and `FLAG_MUTABLE`, because the
  system returns its result as fill-in extras and an immutable intent ignores them (Android's own sample
  shows `FLAG_IMMUTABLE`, which conflicts with the framework code). Always re-check with `hasPermission`.
  Permission lasts only until the device is unplugged unless the user ticks "use by default".
* **Bulk I/O**: use 16 KiB chunks. A synchronous `bulkTransfer` that times out **loses its partial byte
  count**, so use long timeouts for writes (printers legitimately NAK for a long time) and one-packet reads, or
  the async `UsbRequest` API when exact accounting matters. A zero-byte read means "keep reading", never "end".
* **IPP over USB is HTTP/1.1 on the bulk endpoints** with `Host: localhost` and path `/ipp/print`. One
  exchange per interface at a time. A USB pipe cannot be closed like a socket, so **every response must be
  drained completely** or stale bytes poison the next request. Start reading before writing (a printer may
  answer early, for example to upgrade to HTTPS). Use one pipe for the job and a second for status and cancel.
* **Foreground service**: type `connectedDevice`, which has no timeout. Declaring `CHANGE_WIFI_MULTICAST_STATE`
  (needed for mDNS anyway) satisfies its prerequisite for both USB and network jobs. Start it from visible UI
  or the attach-launched activity; USB attach alone is not an exemption from the background-start rules.
* Android 16 Advanced Protection can block *new* USB data connections while the screen is locked.

## Network

* Discover with `NsdManager` for `_ipp._tcp` and `_ipps._tcp`; ignore `_printer._tcp` entries with port 0.
  Use `registerServiceInfoCallback` on API 34+ and a single-flight resolve queue before that.
  De-duplicate by the TXT `UUID`.
* **Android 17 adds `ACCESS_LOCAL_NETWORK`**, enforced only for apps targeting API 37. Platen targets 36 for
  now and does not declare it. `NsdManager`'s system picker avoids the permission.
* A raw `Socket` is not subject to the cleartext-traffic policy that applies to the platform HTTP stacks. This
  is one reason Platen carries its own small HTTP client.
* Prefer IPPS with trust-on-first-use certificate pinning keyed by printer UUID (printers use self-signed certificates).

## Android's `PrintService` API (a later milestone)

* A service receives the document as a **PDF through a non-seekable pipe**. The framework does **not** apply
  page ranges: `getPages()` are indices into the file that was written, and `getCopies()` is the service's job.
* `PrintAttributes` cannot express tray, finishing, quality, n-up or paper type; those need an advanced-options
  activity (a key/value bundle of strings and ints).
* The system keeps the service bound while jobs run, so it needs no foreground service of its own.

## Distribution

* **Play**: new apps and updates must target API 36 from 2026-08-31. Foreground-service use needs a Play
  Console declaration (with a demo video). Declare `android.hardware.usb.host` as `required="false"`.
* **F-Droid**: FLOSS toolchain and dependencies only (Maven Central and Google Maven), no Play Services. A pure
  Kotlin stack means no NDK recipe and no 16 KB page-size work. For reproducible builds disable AGP's
  `dependenciesInfo` blob (done) and watch PNG crunching, VCS info and R8 version.
* Android developer verification (rolling out from 2026-09-30 in some countries, global in 2027) affects
  distribution outside Play too. Watch how it treats sideloaded and F-Droid builds.

## Still unverified (needs a device)

* Whether a zero-length `bulkTransfer` really sends a zero-length packet on OUT.
* Whether `setInterface` fully resets endpoint state on every vendor's controller.
* Whether the USB permission dialog appears when requested from a background service.
* How NSD behaves across vendors (stalled discovery, stale results).
