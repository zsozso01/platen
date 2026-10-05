# 0008. Rendering, layout and PDF handling

**Status:** accepted · **Date:** 2026-10-05

## Context

Research on libraries (`docs/` cites the sources) found:

* Android's own `PdfRenderer` (API 21+) renders arbitrary regions through a matrix and clip, and its documentation
  recommends rendering in stripes for printing. It adds no dependency, no native code, and no F-Droid or
  16 KB-page-size concerns.
* The strong PDF libraries are licence traps: MuPDF, Ghostscript and iText are AGPL; Poppler is GPL.
  PdfBox-Android is unmaintained (last release 2023) and PDFBox 3.x needs `java.awt`.
* PDFium is the only candidate with first-class N-up and form-XObject APIs, but bundling it means native
  binaries (prebuilt-binary and 16 KB alignment questions for F-Droid and Play).
* Many printers cannot take PDF at all (the HP DeskJet 3700 accepts only raster formats over IPP).

## Decision

1. **Layout is computed once** by `core:layout` (page selection, order, scaling, orientation, margins, n-up,
   booklet) as per-side placements: an affine transform, a clip and bounds per source page.
2. **Raster path** (printers without PDF, or any layout that changes the document): render each side in bands
   with `PdfRenderer`, applying the placement transforms and clips at render time, so imposition costs no PDF
   rewriting and stays vector until the final sampling. Encode as PWG Raster (and later URF, PCLm).
3. **PDF pass-through** for PDF-capable printers when the layout is the identity (all pages, original order,
   default scaling). Highest quality and smallest job.
4. **Printer-side features first**: when a PDF-capable printer advertises `page-ranges`, `number-up`, booklet
   finishing and so on, the planner asks the printer to do it. Otherwise it falls back to the raster path.
5. **No PDF library in the MVP.** Vector PDF rewriting (for imposed output at full vector quality) is a later
   step; the candidate is a thin JNI over PDFium, or PDFio (Apache-2.0) if native code must be source-built.

## Consequences

* The MVP has no heavy dependencies and works for both first-target printers.
* A PDF-capable printer printing an *imposed* document goes through the raster path at the printer's
  highest reported resolution: acceptable quality, larger jobs. It is an optimisation to remove later.
* Memory is bounded by the band size (about 2.5 MB for a 256-row A4 band at 300 dpi), never by page size.
* The planner's output is renderer-agnostic, so a future vector-PDF writer reuses it unchanged.
