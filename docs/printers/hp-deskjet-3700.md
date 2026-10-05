# HP DeskJet 3700 series (Wi-Fi) — first validation target

**Status: not yet validated on hardware.** Everything below comes from HP datasheets, HP's open-source
HPLIP code and third-party captures. The "How we will verify" section lists what a real unit must prove.

Covers the 3700 family (3700, 3720, 3722, 3730, 3735, 3750, 3752, 3755, 3760, 3762, 3764, 3772, 3775,
3776, 3785, 3789, 3790). HPLIP lists most of them as one family; the captures we found are of a
"DeskJet 3700 series" and an Ink Advantage 3775 and agree with each other.

## Why this printer matters

It is the opposite of the LaserJet: a low-end inkjet whose native language is HP's proprietary **PCL 3 GUI**.
It accepts **no PDF, no PostScript, no PCL 5/6**. Over Wi-Fi it still prints from phones because it speaks
IPP with *driverless raster formats*. If Platen prints well on this, the transport and raster pipeline are
proven for the large class of cheap driverless inkjets.

## What the printer accepts

| Via | Formats | Source |
|---|---|---|
| Native | HP PCL 3 GUI only | HP datasheet (verified) |
| IPP (`document-format-supported`) | `image/pwg-raster`, `image/urf`, `application/PCLm`, `image/jpeg`, `application/vnd.hp-PCL`, `application/octet-stream` | third-party attribute capture |
| IPP PDF | none (`pdf-versions-supported = none`) | same capture |

**Raster limits over IPP**: PWG Raster and Apple Raster are **300 dpi only**. PCLm accepts 300 and 600 dpi.
HP's headline "1200 / 4800×1200 dpi" applies only to its proprietary driver path.

## Capabilities that shape the app (from the capture)

* **One-sided only** (`sides-supported = one-sided`, no manual-duplex support advertised): Platen must
  implement duplex itself (print odd pages, prompt the user to reload, print even pages).
* **Portrait only** (`orientation-requested-supported = 3`): Platen must rotate landscape pages itself.
* **Fixed unprintable margins**: about 2.96 mm left, right and top; **12.7 mm at the bottom**. No borderless.
* Copies 1–99, `page-ranges-supported = true`, print quality 3/4/5, colour modes auto / monochrome / color.
* Media: A4, Letter, Legal, A5, B5, 4×6, 5×7 and others; one input (`main`), one output (`face-up`).
* **2.4 GHz Wi-Fi only**, no Ethernet. Wi-Fi Direct is supported.
* Supplies are reported (`marker-names`, `marker-levels`).

## How it appears on the network

* DNS-SD `_ipp._tcp`, port 631, TXT `rp=ipp/print`, `pdl=` and `URF=` strings matching the capability
  list above, `Color=T`, `Duplex=F`, `TLS=1.2`.
* IPP URI `ipp://<host>/ipp/print`; an `ipps://<host>:443/ipp/print` endpoint also exists (certificate
  type not verified).
* The name suffix is the last three MAC bytes. When several services appear, filter by `UUID` and the
  `usb_MFG` / `priority` keys: a CUPS-shared copy of the same printer looks similar but is not the printer.
* `_ipps._tcp`, `_pdl-datastream._tcp` and port 9100 were **not** verified. HPLIP prints to this family
  over TCP 9100, so it probably listens, but only for PCL 3 GUI.

## USB

Interfaces reported by the printer: `7/1/2` (bidirectional printer class), `FF/CC/00` (HP scan) and
`FF/04/01` (HP embedded web server). **No IPP-over-USB.** Printing over USB would need a PCL 3 GUI
generator, which HP does not document (HPLIP's BSD-licensed `hpcups` source is the only open reference).
That is out of scope for the MVP, and the printer's owner reports Wi-Fi as the working path anyway.

## Plan for Platen

1. Discover via mDNS (`_ipp._tcp`), or accept a manual address.
2. `Get-Printer-Attributes`; pick `image/pwg-raster` because it is mandatory for IPP Everywhere printers, small and fully specified.
3. Rasterise each page at 300 dpi in `srgb_8` or `sgray_8`, in bands to bound memory (an A4 page is about 26 MB uncompressed), laid out inside the reported margins.
4. `Print-Job` with `job-name`, `copies`, `media`, `print-color-mode`, `print-quality`, `sides=one-sided`.
5. Poll `Get-Job-Attributes` for progress; offer `Cancel-Job`.
6. Do duplex, landscape and page selection in the app.

## How we will verify (needs a real unit)

* [ ] `ipptool` / app capability dump matches the table above (firmware differences?)
* [ ] `Print-Job` with `image/pwg-raster`, `srgb_8` and `sgray_8`, A4 and Letter, 1 and 5 pages
* [ ] Margins and orientation come out right; nothing is clipped at the bottom edge
* [ ] `image/urf` and `application/PCLm` print too (alternative formats)
* [ ] Cancel mid-job works; job state progresses as expected
* [ ] Whether `ipps://:443` uses a self-signed certificate, and whether `_ipps._tcp` is advertised
* [ ] Whether a raw 9100 stream of PWG Raster is accepted (probably not; PCL 3 GUI only)

## Sources

* HP DeskJet 3755 datasheet and user guide (print language, Wi-Fi band, duplex, Android route)
* HPLIP source and PPD for `deskjet_3700_series` (BSD/MIT-licensed parts), including the USB interface comments
* Third-party full `Get-Printer-Attributes` capture and `_ipp._tcp` mDNS capture of a DeskJet 3700 / Ink Advantage 3775
* PWG IPP Everywhere (<https://www.pwg.org/ipp/everywhere.html>) and PWG 5102.4 PWG Raster
