# Printer notes

One page per printer (or family) we have researched or tested. Each page says **what is verified, what is
inferred, and what is unknown**, and ends with a checklist of what a real unit must prove.

| Printer | Connection | Role | State |
|---|---|---|---|
| [HP DeskJet 3700 series](hp-deskjet-3700.md) | Wi-Fi (IPP + PWG Raster) | MVP target: driverless raster inkjet | researched, not yet run |
| [HP LaserJet Managed MFP E42540](hp-laserjet-managed-mfp-e42540.md) | USB (IPP-over-USB or PJL + PDF) | MVP target: PDF laser | researched, not yet run |

Neither printer is special-cased in the app. They are the first two *validation* targets: they cover a
raster-only network inkjet and a PDF/PostScript/PCL USB laser, which together exercise most of the
architecture.

## Adding your printer

Use the in-app diagnostic export (when available) or `ipptool -tv ipp://<printer>/ipp/print get-printer-attributes.test`,
remove serial numbers, host names and addresses, and open a
[printer compatibility report](https://github.com/zsozso01/platen/issues/new?template=printer_report.yml).
