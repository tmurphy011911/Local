package live.localapp.local

object ChatSheetBridge {
    var onOpenSheet: (() -> Unit)? = null
    var onCloseSheet: (() -> Unit)? = null
    var onOpenComplete: (() -> Unit)? = null
    var onCloseComplete: (() -> Unit)? = null
}
