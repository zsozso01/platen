# 0010. A PDF made of page images for printers without raster formats

**Status:** accepted · **Date:** 2026-10-05

## Context

A printer that takes PDF, PostScript or PCL but no raster format (the E42540 over PJL, many lasers) could
only receive a PDF unchanged. Anything that changes the layout (page ranges, reverse order, n-up, booklets,
margins, manual duplex) or any image document had no route, and the planner ignored such settings with a
warning. Vector PDF rewriting needs a PDF library; the good ones are AGPL/GPL or native (ADR 0008).

## Decision

When pass-through is not possible and the printer takes PDF but not PWG Raster, the planner uses the
raster route with PDF as the format: each face is rendered by the same banded rasteriser and written as one
full-sheet Flate-compressed image on a page of the sheet's exact size. Resolution stops at 600 dpi (a PDF
image's density only affects sharpness, and 1200 dpi pages would be about a hundred megabytes). Back sides need
no PWG transforms. A printer that takes PWG Raster keeps using it.

The writer streams bands through Deflate and writes image lengths as indirect objects after the data, so memory
stays at a few bands; this is ordinary PDF (pdfTeX and Cairo do it).

## Consequences

* Text on such pages is pixels, not vectors, and files are larger than the original PDF; a PDF that can be sent
  unchanged still is.
* Printers may scale a full-sheet page to fit their printable area unless told not to (IPP `print-scaling`
  `none` is sent; PJL has no equivalent), so a slight shrink is possible on the PJL route. To check on hardware.
* A later vector rewriter can replace the rasterising without touching the planner's decisions.
