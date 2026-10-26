package id.gerry.gbook.ui.reader

/**
 * Tiny bridge so the Activity can talk to whichever Reader is on screen.
 *
 * The reader registers [controller] while it is showing; MainActivity consults it for volume-key
 * handling (volume up = previous sentence, volume down = next sentence). Cleared when
 * the reader leaves.
 */
internal object ReaderBridge {
    var controller: Controller? = null

    interface Controller {
        fun nextSentence()
        fun prevSentence()
        fun playPause()
    }
}
