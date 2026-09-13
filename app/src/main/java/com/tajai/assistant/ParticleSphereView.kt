package com.tajai.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A particle sphere that slowly rotates, made of ~180 dots distributed evenly on a
 * sphere (golden-spiral method) and projected to 2D. Dots further "back" are drawn
 * smaller/dimmer for a 3D feel — same visual idea as the Archer AI reference video.
 *
 * Call setEnergy(0f..1f) to make it pulse faster/bigger — drive this from mic RMS
 * level while listening, or from a steady pulse while TTS is speaking.
 */
class ParticleSphereView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Dot(val theta: Double, val phi: Double, val colorSeed: Float)

    private val dots = mutableListOf<Dot>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rotation = 0.0
    private var energy = 0f // 0 = idle, 1 = fully active (listening/speaking)
    private var pulsePhase = 0f

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 18000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = (it.animatedValue as Float).toDouble()
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        val n = 180
        val goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0))
        for (i in 0 until n) {
            val y = 1 - (i / (n - 1.0)) * 2 // -1..1
            val radiusAtY = Math.sqrt(1 - y * y)
            val theta = goldenAngle * i
            dots.add(Dot(theta, Math.acos(y), Random.nextFloat()))
        }
    }

    fun setEnergy(value: Float) {
        energy = value.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rotationAnimator.start()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2.2f
        val breathe = 1f + 0.04f * pulsePhase + 0.08f * energy

        // Sort by depth (z) each frame so far dots draw first (simple painter's algorithm)
        val projected = dots.map { dot ->
            val phi = dot.phi
            val theta = dot.theta + Math.toRadians(rotation)
            val x = sin(phi) * cos(theta)
            val y = cos(phi)
            val z = sin(phi) * sin(theta)
            Triple(x, y, z) to dot
        }.sortedBy { it.first.third }

        for ((pos, dot) in projected) {
            val (x, y, z) = pos
            val scale = (z + 1.6) / 2.6 // 0.15..1.0 roughly, closer dots bigger
            val px = cx + (x * baseRadius * breathe).toFloat()
            val py = cy + (y * baseRadius * breathe).toFloat()
            val dotRadius = (2.2f + 2.4f * scale.toFloat()) * (1f + 0.3f * energy)

            // Color: mix of emerald / cyan / warm gold, like the reference video
            paint.color = when {
                dot.colorSeed < 0.5f -> android.graphics.Color.argb(
                    (160 + 90 * scale).toInt().coerceIn(0, 255), 16, 185, 129
                )
                dot.colorSeed < 0.8f -> android.graphics.Color.argb(
                    (160 + 90 * scale).toInt().coerceIn(0, 255), 6, 182, 212
                )
                else -> android.graphics.Color.argb(
                    (160 + 90 * scale).toInt().coerceIn(0, 255), 250, 204, 21
                )
            }
            canvas.drawCircle(px, py, dotRadius, paint)
        }
    }
}
