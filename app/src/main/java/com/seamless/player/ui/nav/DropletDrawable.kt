package com.seamless.player.ui.nav

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The liquid style's drop of glass.
 *
 * Four quiet layers in the shape of a capsule. A body of white, brighter at the top than the
 * bottom. Light pooling along the lower edge, the way light gathers at the thick rim of a real
 * drop. A thin dark hairline, which is what keeps a clear shape visible on a light page. And a
 * specular rim, brightest at the top left and fading round to the bottom right.
 *
 * [lift] runs from 0 at rest to 1 while touched or travelling: the body clears and the rim
 * brightens, which is what makes the drop read as lifted off the surface rather than just larger.
 *
 * The gradients are built once in a unit square and stretched to the drop with a matrix at draw
 * time, because the drop changes shape on every frame it moves and must not allocate as it does.
 */
internal class DropletDrawable(private val density: Float) : Drawable() {

    var night = false
        set(value) {
            field = value
            buildShaders()
            invalidateSelf()
        }

    var lift = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private val stretch = Matrix()
    private lateinit var bodyShader: Shader
    private lateinit var poolShader: Shader
    private lateinit var rimShader: Shader

    init {
        buildShaders()
    }

    private fun buildShaders() {
        bodyShader = LinearGradient(
            0f, 0f, 0f, 1f,
            if (night) 0x38FFFFFF else 0xB8FFFFFF.toInt(),
            if (night) 0x12FFFFFF else 0x6BFFFFFF,
            Shader.TileMode.CLAMP,
        )
        poolShader = LinearGradient(
            0f, 0.5f, 0f, 1f,
            0x00FFFFFF,
            if (night) 0x24FFFFFF else 0x66FFFFFF,
            Shader.TileMode.CLAMP,
        )
        rimShader = LinearGradient(
            0f, 0f, 1f, 1f,
            intArrayOf(
                if (night) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt(),
                if (night) 0x14FFFFFF else 0x33FFFFFF,
                if (night) 0x66FFFFFF else 0xCCFFFFFF.toInt(),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        body.shader = bodyShader
        pool.shader = poolShader
        rim.shader = rimShader
        edge.color = if (night) 0x4D000000 else 0x1A000000
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        shape.set(b)
        stretch.setScale(shape.width(), shape.height())
        stretch.postTranslate(shape.left, shape.top)
        bodyShader.setLocalMatrix(stretch)
        poolShader.setLocalMatrix(stretch)
        rimShader.setLocalMatrix(stretch)

        val clear = lift.coerceIn(0f, 1f)
        val radius = shape.height() / 2f
        body.alpha = (255 * (1f - 0.4f * clear)).toInt()
        canvas.drawRoundRect(shape, radius, radius, body)
        pool.alpha = (255 * (0.7f + 0.3f * clear)).toInt()
        canvas.drawRoundRect(shape, radius, radius, pool)

        val hair = 0.8f * density
        edge.strokeWidth = hair
        shape.inset(hair / 2f, hair / 2f)
        canvas.drawRoundRect(shape, radius - hair / 2f, radius - hair / 2f, edge)

        val line = 1.3f * density
        rim.strokeWidth = line
        rim.alpha = (255 * (0.75f + 0.25f * clear)).toInt()
        shape.inset((line + hair) / 2f, (line + hair) / 2f)
        val inner = radius - hair - line / 2f
        canvas.drawRoundRect(shape, inner, inner, rim)
    }

    /**
     * Opaque as far as shadows are concerned. A translucent caster has its shadow filled in
     * underneath it, which would put a grey smudge inside a clear drop; an opaque one casts only
     * the soft edge around it, which is the lift wanted.
     */
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, bounds.height() / 2f)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
