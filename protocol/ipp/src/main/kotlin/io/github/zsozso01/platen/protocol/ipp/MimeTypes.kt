package io.github.zsozso01.platen.protocol.ipp

/** `document-format` values Platen knows about. Printers may support others; those are passed through as strings. */
public object IppFormats {
    public const val PDF: String = "application/pdf"
    public const val POSTSCRIPT: String = "application/postscript"
    public const val PCL: String = "application/vnd.hp-pcl"
    public const val PWG_RASTER: String = "image/pwg-raster"
    public const val URF: String = "image/urf"
    public const val PCLM: String = "application/PCLm"
    public const val JPEG: String = "image/jpeg"
    public const val PNG: String = "image/png"
    public const val TEXT: String = "text/plain"

    /** "Let the printer work out the format". Many printers accept it; few handle it well. */
    public const val AUTO: String = "application/octet-stream"
}
