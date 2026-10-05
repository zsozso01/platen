# HP LaserJet Managed MFP E42540 (USB) — first validation target

**Status: not yet validated on hardware.** The facts below come from HP datasheets, HP's user guide and
open-source HP and OpenPrinting code. Several things we most need to know have **not** been found
anywhere public; they are listed under "Unknown" and are the first things a real unit should answer.

## Why this printer matters

A modern enterprise laser MFP: it speaks real page-description languages natively and has a proper USB
*device* port, so a phone can be the USB host through an OTG adapter. It validates the USB transport and
the "printer takes PDF directly" path.

## What the printer accepts

| Language | Status | Source |
|---|---|---|
| PDF 1.7 (native) | verified | HP datasheet |
| PostScript level 3 emulation | verified | HP datasheet |
| PCL 6 (PCL XL) and PCL 5 | verified | HP datasheet |
| PWG Raster / Apple Raster / PCLm over IPP | **unknown** (Mopria-certified, so at least one IPP route exists) | |

It is a FutureSmart 5 device. HP's own Linux driver treats it as a PostScript printer.

## USB

* It has one USB **device** port (rear, "USB interface port") plus two **host** ports for flash drives.
  Only the device port is useful to a phone.
* **An administrator can turn the device port off** (embedded web server: Security → Hardware Ports →
  "Enable Device USB"). A "Managed" fleet printer may well have it disabled. Platen must say so clearly
  when the port is silent, not just fail.
* USB ID `03f0:d72a`. Windows lists a composite device with driver packages named "REST", "IPP WinUSB" and
  "IPP1 WinUSB", which strongly suggests **IPP-over-USB interfaces** next to the classic printer interface.
* The exact interface class/subclass/protocol numbers are **unknown**. OpenPrinting's `ipp-usb` recognises
  both the standard `7/1/4` and an HP-specific `255/9/1` as IPP-over-USB, so Platen accepts both and lets a
  probe decide. Do not hard-code `7/1/4`.
* FutureSmart devices can be slow to initialise over IPP-over-USB (`ipp-usb` quirk files use long
  initialisation timeouts and retries; `503 Service not ready` early in boot is known).

## Job control: PJL, or inside the language?

* PJL exists on this printer (the user guide has "PJL password" and "PJL access commands" settings, and
  the access commands can be locked down).
* HP's own PostScript filter for this printer uses PJL only for framing (`@PJL JOB`, `ENTER LANGUAGE`,
  `EOJ`), accounting and a few settings (`ECONOMODE`, `RESOLUTION`). **Duplex, tray and copies travel
  inside the PostScript** as `setpagedevice` calls.
* For raw **PDF**, PJL `SET` variables are the only way to control the job (a 2010-era tested recipe on
  another LaserJet used `@PJL SET OUTBIN`, `MEDIASOURCE`, then `ENTER LANGUAGE = PDF`). Which variables this
  E42540 honours **for PDF** is **unknown** and must be tested.
* Therefore Platen asks the printer: `@PJL INFO VARIABLES` lists what it will accept, and Platen only sends
  variables the printer lists. Where the printer does not honour a PJL setting for PDF, Platen falls back to
  PostScript with in-language `setpagedevice`, or to IPP.

## How Platen drives it (built, simulated, not yet tried on this printer)

1. On plug-in Platen lists the device and asks Android for permission. It prefers IPP over USB (`7/1/4` or
   `255/9/1`, two or more interfaces) and falls back to the classic interface (`7/1/2`).
2. Over IPP-over-USB: `Get-Printer-Attributes` (waiting out `503` while the printer boots), then
   `application/pdf` with IPP job attributes (`sides`, `media`, `copies`, ...), then job polling.
3. Over the classic interface: read the Device ID, ask `@PJL INFO ID/CONFIG/VARIABLES/STATUS`, and send
   `UEL`, `USTATUS` requests, `@PJL JOB`, only the `@PJL SET` variables the printer listed,
   `ENTER LANGUAGE=PDF`, the PDF, `UEL`, `@PJL EOJ`. Job start/end and attention (paper out) come back as
   `USTATUS`. Copies use `QTY` when listed (collated job copies), else `COPIES`.
4. A PDF that can be sent unchanged goes through untouched. Anything that changes the layout (page ranges,
   reverse order, n-up, booklets, custom margins, manual duplex) and image documents are rendered into a PDF of
   page images at up to 600 dpi, so every Platen setting works on this printer.
5. Troubleshooting modes (per printer, saved in `printers.json`): IPP over USB only, PJL only, raw (bare document).

## First test on a real unit

Plug it into the phone with an OTG cable, add it in Platen, print a one-page PDF, then use **Diagnostics**
and paste the text into a [printer report](https://github.com/zsozso01/platen/issues/new?template=printer_report.yml).
It contains no serial number or document name. What to look for:

* The *Add* dialog lists it, and after "Allow" the card shows *Ready*. If it says the printer did not answer,
  check **Enable Device USB** (see above).
* Which route was used: the log says `usb: using IPP over USB` or `PJL on printer interface N`.
* Whether the job completed *and the sheet came out*. PJL completion relies on the `USTATUS JOB END` report;
  an end with `PAGES=0` is treated as a failure, which is an **assumption** to confirm.
* Duplex, tray and copies: whether the printer honours the PJL `SET` variables for PDF, or whether only
  IPP does. If PJL ignores them, that is the case for sending PostScript with `setpagedevice` (not built).
* Cancelling mid-job, and unplugging mid-job: what the app shows, and whether the printer recovers.

## Unknown (what a real unit must answer)

* [ ] `lsusb -v -d 03f0:d72a`: the interfaces and endpoints
* [ ] The real IEEE 1284 Device ID
* [ ] Whether IPP-over-USB answers, and on which interface; initialisation time
* [ ] The full IPP attribute dump (formats, sides, trays, media, resolutions)
* [ ] Which PJL `SET` variables are honoured for PDF: `DUPLEX`, `BINDING`, `COPIES`, `QTY`, `PAPER`, `MEDIASOURCE`, `OUTBIN`, `RENDERMODE`, `RESOLUTION`, `ECONOMODE`
* [ ] Whether `USTATUS` and `INFO` work over the USB printer interface
* [ ] Whether Device USB is enabled by default on this unit

## Sources

* HP LaserJet Managed MFP E42540 datasheet and user guide (languages, ports, EWS hardware-port and PJL settings)
* HPLIP data for `hp_laserjet_mfp_e42540` (USB ID, PostScript PPD, `hppsfilter.c` job framing)
* OpenPrinting `ipp-usb` source and HP quirk files (interface patterns, initialisation behaviour)
* HP PJL Technical Reference (variable semantics)
