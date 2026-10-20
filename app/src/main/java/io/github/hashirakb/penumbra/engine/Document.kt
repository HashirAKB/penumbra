package io.github.hashirakb.penumbra.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import io.github.hashirakb.penumbra.core.Journal
import io.github.hashirakb.penumbra.core.LayerBlend

class Layer(val id: Int, var name: String, var bitmap: Bitmap) {
    var visible = true
    var opacity = 1f
    var blend = LayerBlend.NORMAL
    var alphaLock = false
    var locked = false

    /** Bumped on every pixel change; used for thumbnails and incremental saving. */
    var version = 0
        private set
    var savedVersion = -1

    fun touch() { version++ }

    fun props() = LayerProps(name, visible, opacity, blend, alphaLock, locked)
    fun setProps(p: LayerProps) {
        name = p.name; visible = p.visible; opacity = p.opacity
        blend = p.blend; alphaLock = p.alphaLock; locked = p.locked
    }
}

data class LayerProps(
    val name: String, val visible: Boolean, val opacity: Float,
    val blend: LayerBlend, val alphaLock: Boolean, val locked: Boolean,
)

/**
 * A drawing: a stack of full-size layers (index 0 is the bottom), a paper color, the journal
 * that records how it was made, and image assets the journal refers to.
 */
class Document(val width: Int, val height: Int, background: Int) {
    /** Current paper color. The journal keeps the color the drawing started with. */
    var background: Int = background
    val layers = ArrayList<Layer>()
    var activeId = 0
    val journal = Journal(width, height, background)
    val assets = LinkedHashMap<String, Bitmap>()
    var nextLayerId = 1

    /** Bumped on any change; drives autosave and gallery thumbnails. */
    var revision = 0
        private set
    fun markChanged() { revision++ }

    val active: Layer? get() = layer(activeId) ?: layers.lastOrNull()
    fun layer(id: Int): Layer? = layers.firstOrNull { it.id == id }
    fun indexOf(id: Int) = layers.indexOfFirst { it.id == id }

    fun newBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun allocateLayerId(): Int = nextLayerId++

    /** Rough layer budget so we never run the app out of memory. */
    fun maxLayers(): Int {
        val perLayer = width.toLong() * height * 4
        val budget = Runtime.getRuntime().maxMemory() * 45 / 100
        return (budget / perLayer).toInt().coerceIn(3, 64)
    }

    companion object {
        fun blendMode(b: LayerBlend): BlendMode = when (b) {
            LayerBlend.NORMAL -> BlendMode.SRC_OVER
            LayerBlend.MULTIPLY -> BlendMode.MULTIPLY
            LayerBlend.SCREEN -> BlendMode.SCREEN
            LayerBlend.OVERLAY -> BlendMode.OVERLAY
            LayerBlend.DARKEN -> BlendMode.DARKEN
            LayerBlend.LIGHTEN -> BlendMode.LIGHTEN
            LayerBlend.COLOR_DODGE -> BlendMode.COLOR_DODGE
            LayerBlend.COLOR_BURN -> BlendMode.COLOR_BURN
            LayerBlend.HARD_LIGHT -> BlendMode.HARD_LIGHT
            LayerBlend.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
            LayerBlend.DIFFERENCE -> BlendMode.DIFFERENCE
            LayerBlend.EXCLUSION -> BlendMode.EXCLUSION
            LayerBlend.HUE -> BlendMode.HUE
            LayerBlend.SATURATION -> BlendMode.SATURATION
            LayerBlend.COLOR -> BlendMode.COLOR
            LayerBlend.LUMINOSITY -> BlendMode.LUMINOSITY
            LayerBlend.ADD -> BlendMode.PLUS
        }
    }
}
