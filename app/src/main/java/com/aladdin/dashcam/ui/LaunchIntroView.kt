package com.aladdin.dashcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aladdin.dashcam.R
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** A short, local pixel animation; it never waits for recorder connectivity. */
class LaunchIntroView(context: Context, private val completed: () -> Unit) : FrameLayout(context) {
    companion object {
        private const val PREFERENCES = "launch_intro"
        private const val REPEAT = "repeat"
        private const val SEEN = "seen"
        private const val DURATION_MS = 2800L
        private const val HOLD_MS = 1200L
        private const val FRAME_INTERVAL_MS = 62L

        fun repeats(context: Context): Boolean = preferences(context).getBoolean(REPEAT, true)
        fun setRepeats(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean(REPEAT, enabled).apply() }
        fun shouldShow(context: Context): Boolean = repeats(context) || !preferences(context).getBoolean(SEEN, false)
        private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    private val artwork = PixelScene(context)
    private var brandPanel: LinearLayout? = null
    private var contentLayout: LinearLayout? = null
    private var wideLayout: Boolean? = null
    private var finished = false
    private var startTime = 0L
    private var pausedAt = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (finished || !isAttachedToWindow || windowVisibility != VISIBLE) return
            val now = SystemClock.uptimeMillis()
            if (startTime == 0L) startTime = now
            val elapsed = now - startTime
            if (elapsed >= DURATION_MS + HOLD_MS) { finish(); return }
            val p = (elapsed.toFloat() / DURATION_MS).coerceIn(0f, 1f)
            if (artwork.phase != p) artwork.phase = p
            brandPanel?.alpha = ((p - 0.26f) / 0.24f).coerceIn(0f, 1f)
            if (p < 1f) {
                Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_INTERVAL_MS)
            } else {
                // Keep the final glorious smoke + rider scene for 1200ms before smooth transition
                Choreographer.getInstance().postFrameCallbackDelayed(this, DURATION_MS + HOLD_MS - elapsed)
            }
        }
    }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg_dark))
        isClickable = true
        isFocusable = true
        setOnClickListener { finish() }

        buildComposition(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun buildComposition(wide: Boolean) {
        wideLayout = wide
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        (artwork.parent as? android.view.ViewGroup)?.removeView(artwork)
        contentLayout?.let { removeView(it) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        contentLayout = content
        addView(content, LayoutParams(-1, -1))
        val stage = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        stage.addView(artwork, if (wide) LinearLayout.LayoutParams(0, -1, 1.1f)
            else LinearLayout.LayoutParams(-1, 0, 1f))
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (wide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            setPadding(dp(if (wide) 20 else 0), 0, 0, dp(if (wide) 0 else 24))
            alpha = ((artwork.phase - 0.26f) / 0.24f).coerceIn(0f, 1f)
        }
        brandPanel = brand
        stage.addView(brand, if (wide) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, -2))
        brand.addView(TextView(context).apply {
            text = context.getString(R.string.intro_brand).uppercase(java.util.Locale.ROOT)
            textSize = 11f
            gravity = if (wide) Gravity.START else Gravity.CENTER
            letterSpacing = 0.16f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        brand.addView(TextView(context).apply {
            setText(R.string.intro_slogan)
            textSize = if (wide) 25f else 23f
            typeface = Typeface.DEFAULT_BOLD
            gravity = if (wide) Gravity.START else Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }, LinearLayout.LayoutParams(-1, -2))
        brand.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.accent_blue))
        }, LinearLayout.LayoutParams(dp(36), dp(3)).apply { topMargin = dp(4) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))
        actions.addView(CheckBox(context).apply {
            setText(R.string.intro_repeat)
            textSize = 12f
            minHeight = dp(48)
            isChecked = repeats(context)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setOnCheckedChangeListener { _, checked -> setRepeats(context, checked) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.intro_skip)
            textSize = 13f
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && wideLayout != (w > h)) buildComposition(w > h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.uptimeMillis()
        pausedAt = 0L
        artwork.phase = 0f
        if (windowVisibility == VISIBLE) Choreographer.getInstance().postFrameCallback(frameCallback)
        else pausedAt = startTime
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow || startTime == 0L || finished) return
        val clock = Choreographer.getInstance()
        clock.removeFrameCallback(frameCallback)
        if (visibility == VISIBLE) {
            if (pausedAt > 0L) startTime += SystemClock.uptimeMillis() - pausedAt
            pausedAt = 0L
            clock.postFrameCallback(frameCallback)
        } else if (pausedAt == 0L) pausedAt = SystemClock.uptimeMillis()
    }

    fun finish() {
        if (finished) return
        finished = true
        stopAnimation()
        preferences(context).edit().putBoolean(SEEN, true).apply()
        completed()
    }

    private fun stopAnimation() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private class PixelScene(context: Context) : View(context) {
        var phase = 0f
            set(value) { field = value; invalidate() }

        private val paint = Paint().apply { isAntiAlias = false }

        // Palette for the refined lamp
        private val ink = 0xff141c28.toInt()
        private val goldHi = 0xffffe58f.toInt()
        private val goldMid = 0xffffb703.toInt()
        private val goldDark = 0xffc67d00.toInt()
        private val goldBronze = 0xff8c5300.toInt()

        // Palette for magic genie smoke
        private val smokeWhite = 0xffffffff.toInt()
        private val smokeIce = 0xffe1f5fe.toInt()
        private val smokeCyan = 0xff40c4ff.toInt()
        private val smokeBlue = 0xff0288d1.toInt()
        private val smokeDeep = 0xff0d47a1.toInt()

        // Palette for rider
        private val palette = mapOf(
            'K' to 0xff182538.toInt(),
            'W' to 0xfffff1d0.toInt(),
            'B' to 0xff1672e8.toInt(),
            'D' to 0xff204b82.toInt(),
            'G' to 0xff94a5b7.toInt(),
            'R' to 0xffef674c.toInt()
        )

        private val rider = arrayOf(
            "..............KKKKK.............",
            ".............KWWWWWKK...........",
            "............KWWWWWWWWK..........",
            "............KWKKKWWWWK..........",
            "............KKBDKKKKKK..........",
            "............KKKKKKWWWK..........",
            ".............KWWWKWWK...........",
            "...........KKKBKKKKK............",
            "..........KBBBBK................",
            ".........KBBBBBK................",
            "........KBBKBBBK................",
            "........KBBKBBBBKK..............",
            "........KBBKBBBBBBKKKK..........",
            "........KBBKDKBBBBBKGGK.........",
            "........KKKKKKKKKKKKKK.........",
            ".........KGGGGKK....KWWK........",
            "..RBBBBBKKKGGGGK...KWWWK........",
            "..KKBBBBBBKKGGGKBBBKWWK.........",
            "....KWWBBBBKGGKBBBBBKDKBBBB.....",
            ".....KWWWBBKGGKBBBBBKDKBBBK.....",
            "......KWWWKGGKBBBBBK.GK.........",
            "....KKKKKKGGKDDDDK..KGK.........",
            "...KKGGKKKGGKKKKK..KKGGKK.......",
            "..KKGKKGKKKKKGGKK.KKGKKGKK......",
            "..KGK..KGKKGGGGK..KGK..KGK......",
            "..KGK..KGKKKKKK...KGK..KGK......",
            "..KKGKKGKK........KKGKKGKK......",
            "...KKGGKK..........KKGGKK.......",
            "....KKKK............KKKK........"
        )

        // Refined organic cloud puffs configuration
        private class CloudPuff(
            val cx: Float,
            val cy: Float,
            val rx: Float,
            val ry: Float,
            val birth: Float,
            val isCore: Boolean
        )

        private val cloudPuffs = arrayOf(
            CloudPuff(18f, 39f, 4.5f, 3.2f, 0.12f, false),
            CloudPuff(22f, 33f, 6.0f, 4.2f, 0.16f, false),
            CloudPuff(21f, 26f, 7.5f, 5.0f, 0.20f, false),
            CloudPuff(27f, 30f, 8.5f, 5.8f, 0.24f, true),
            CloudPuff(32f, 24f, 10.0f, 6.8f, 0.28f, true),
            CloudPuff(38f, 28f, 8.5f, 5.8f, 0.30f, true),
            CloudPuff(26f, 18f, 7.5f, 5.0f, 0.32f, false),
            CloudPuff(34f, 16f, 8.5f, 5.5f, 0.35f, true),
            CloudPuff(41f, 22f, 7.5f, 5.0f, 0.36f, false),
            CloudPuff(43f, 31f, 6.0f, 4.0f, 0.38f, false),
            // Floating pedestal clouds supporting the bike
            CloudPuff(26f, 37f, 7.0f, 4.0f, 0.22f, false),
            CloudPuff(36f, 37f, 7.5f, 4.2f, 0.26f, false)
        )

        // Sparkle motes drifting from spout
        private class Mote(val birth: Float, val targetX: Float, val targetY: Float, val size: Float)
        private val motes = run {
            val list = mutableListOf<Mote>()
            val count = 20
            for (i in 0 until count) {
                val birth = 0.12f + (i.toFloat() / count) * 0.40f
                val ang = (i * 1.72f) % (Math.PI.toFloat() * 2f)
                val dist = 5f + ((i * 13) % 18).toFloat()
                val tx = 32f + cos(ang) * dist
                val ty = 25f + sin(ang) * (dist * 0.7f)
                val sz = if (i % 3 == 0) 2f else 1f
                list.add(Mote(birth, tx, ty, sz))
            }
            list
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = floor(minOf(width / 64f, height / 70f))
            if (unit <= 0) return

            canvas.save()
            canvas.translate((width - unit * 64) / 2, (height - unit * 70) / 2)
            canvas.scale(unit, unit)

            fun block(x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Int = 255) {
                paint.color = color
                paint.alpha = alpha.coerceIn(0, 255)
                val left = round(x); val top = round(y)
                canvas.drawRect(left, top, round(x + w).coerceAtLeast(left + 1f), round(y + h).coerceAtLeast(top + 1f), paint)
            }

            // Quiet pixel constellation and a stepped halo frame the summoning stage.
            for (i in 0 until 28) {
                val x = ((i * 23 + 5) % 62).toFloat()
                val y = ((i * 17 + 3) % 66).toFloat()
                block(x, y, 1f, 1f, smokeIce, 20 + i % 4 * 8)
            }
            val halo = ((phase - 0.12f) / 0.3f).coerceIn(0f, 1f)
            for (row in 0 until 46) {
                val dy = (row - 23) / 23f
                val span = (23f * sqrt((1f - dy * dy).coerceAtLeast(0f))).toInt()
                if (span > 0) block(32f - span, row + 4f, span * 2f, 1f, smokeBlue, (halo * 14).toInt())
            }
            block(17f, 68f, 30f, 1f, smokeBlue, 45)
            block(24f, 67f, 16f, 1f, smokeCyan, 32)

            // Organic stepped cloud puff drawing with layered shading
            fun drawOrganicPuff(cx: Float, cy: Float, rx: Float, ry: Float, alpha: Int) {
                if (rx < 1f || ry < 1f || alpha <= 0) return
                val iry = ry.toInt()
                // 1. Dark bottom shadow / outer rim
                paint.color = smokeDeep
                paint.alpha = (alpha * 0.65f).toInt().coerceIn(0, 255)
                for (dy in -iry..iry) {
                    val ny = dy / ry
                    if (ny < -1f || ny > 1f) continue
                    val span = (rx * sqrt(1f - ny * ny)).toInt()
                    if (span > 0) block(cx - span, cy + dy, span * 2f + 1f, 1f, smokeDeep, (alpha * 0.65f).toInt())
                }

                // 2. Main cyan body (shifted slightly upwards)
                val cyCyan = cy - 0.4f
                val rxCyan = rx * 0.85f
                val ryCyan = ry * 0.82f
                val iryC = ryCyan.toInt()
                paint.color = smokeCyan
                paint.alpha = alpha.coerceIn(0, 255)
                for (dy in -iryC..iryC) {
                    val ny = dy / ryCyan
                    if (ny < -1f || ny > 1f) continue
                    val span = (rxCyan * sqrt(1f - ny * ny)).toInt()
                    if (span > 0) block(cx - span, cyCyan + dy, span * 2f + 1f, 1f, smokeCyan, alpha)
                }

                // 3. Ice highlight on upper half
                val cyIce = cy - 1.0f
                val rxIce = rx * 0.60f
                val ryIce = ry * 0.52f
                val iryIce = ryIce.toInt()
                paint.color = smokeIce
                paint.alpha = (alpha * 0.95f).toInt().coerceIn(0, 255)
                for (dy in -iryIce..0) {
                    val ny = dy / ryIce
                    if (ny < -1f || ny > 1f) continue
                    val span = (rxIce * sqrt(1f - ny * ny)).toInt()
                    if (span > 0) block(cx - span, cyIce + dy, span * 2f + 1f, 1f, smokeIce, (alpha * 0.95f).toInt())
                }

                // 4. Pure white glowing core crest
                val rxWhite = rx * 0.32f
                if (rxWhite >= 1f) {
                    paint.color = smokeWhite
                    paint.alpha = (alpha * 0.90f).toInt().coerceIn(0, 255)
                    block(cx - rxWhite, cy - 1.6f, rxWhite * 2f + 1f, 1f, smokeWhite, (alpha * 0.90f).toInt())
                }
            }

            // --- LAYER 1: S-CURVE GENIE RIBBON FROM SPOUT ---
            val spoutX = 14.5f
            val spoutY = 46.0f
            if (phase >= 0.12f) {
                val tailProgress = ((phase - 0.12f) / 0.38f).coerceIn(0f, 1f)
                val steps = (24 * tailProgress).toInt()
                for (s in 0..steps) {
                    val t = s.toFloat() / 24f
                    val inv = 1f - t

                    // S-curve cubic bezier: spout -> loops up-left -> sweeps right -> ascends under bike
                    var sx = inv*inv*inv * spoutX + 3f*inv*inv*t * 11.0f + 3f*inv*t*t * 22.0f + t*t*t * 32.0f
                    var sy = inv*inv*inv * spoutY + 3f*inv*inv*t * 37.0f + 3f*inv*t*t * 42.0f + t*t*t * 37.0f

                    // Gentle dynamic wave
                    val wave = sin((phase * 16f - t * 4f).toDouble()).toFloat() * (0.6f + t * 1.2f)
                    sx += wave
                    val w = 1.0f + t * 3.5f

                    // Stream ribbon outer cyan & inner white pulse
                    block(sx - w, sy - w * 0.6f, w * 2f, w * 1.2f, smokeCyan, (220 * tailProgress).toInt())
                    val pulse = sin((phase * 22f - t * 6f).toDouble()) > 0.1
                    val coreColor = if (pulse) smokeWhite else smokeIce
                    block(sx - w * 0.45f, sy - w * 0.3f, w * 0.9f, w * 0.6f, coreColor, (245 * tailProgress).toInt())
                }
            }

            // --- LAYER 2: SPOUT JET & PLUME ---
            if (phase >= 0.10f) {
                val jetTick = (phase * 32).toInt()
                val jSize = 1.8f + (jetTick % 3) * 0.7f
                block(spoutX - 1.8f, spoutY - 2.8f, jSize, jSize, smokeIce, 245)
                block(spoutX - 0.5f, spoutY - 3.8f, 1.8f, 1.8f, smokeWhite, 255)
            }

            // --- LAYER 3: BILLOWING ORGANIC PUFFS ---
            if (phase >= 0.14f) {
                for (i in cloudPuffs.indices) {
                    val p = cloudPuffs[i]
                    if (phase < p.birth) continue
                    val growth = ((phase - p.birth) / 0.28f).coerceIn(0f, 1f)

                    // Organic swirling drift
                    val driftX = sin((phase * 14f + i * 1.4f).toDouble()).toFloat() * (0.8f + growth * 1.4f)
                    val driftY = cos((phase * 11f + i * 1.1f).toDouble()).toFloat() * (0.6f + growth * 1.0f)
                    val cx = p.cx + driftX
                    val cy = p.cy + driftY

                    // Alpha: core clouds soften during bike summon, pedestal clouds stay dense and hover
                    val clearing = if (p.isCore) ((phase - 0.52f) / 0.34f).coerceIn(0f, 1f)
                        else if (p.cy < 37f) ((phase - 0.70f) / 0.25f).coerceIn(0f, 1f)
                        else 0f
                    val alpha = (235 * growth * (1f - 0.92f * clearing)).toInt()

                    drawOrganicPuff(cx, cy, p.rx * growth, p.ry * growth, alpha)
                }
            }

            // --- LAYER 4: FLOATING MAGIC MOTES ---
            if (phase in 0.15f..0.85f) {
                for (i in motes.indices) {
                    val m = motes[i]
                    if (phase < m.birth) continue
                    val prog = ((phase - m.birth) / 0.45f).coerceIn(0f, 1f)
                    val inv = 1f - prog
                    val mx = inv * spoutX + prog * m.targetX + sin((phase * 20f + i).toDouble()).toFloat() * 1.5f
                    val my = inv * spoutY + prog * m.targetY - prog * 4f
                    val mAlpha = (240 * sin(prog * Math.PI).toFloat()).toInt().coerceIn(0, 255)
                    val mCol = if (i % 2 == 0) smokeWhite else smokeCyan
                    block(mx, my, m.size, m.size, mCol, mAlpha)
                }
            }

            // --- LAYER 5: RIDER SUMMONING (AETHERIC SILHOUETTE -> SOLID WAVE) ---
            val riderLeft = 16f
            val riderTop = 9f
            if (phase >= 0.50f) {
                val silhouetteAlpha = ((phase - 0.50f) / 0.12f).coerceIn(0f, 1f)
                val solidWave = ((phase - 0.60f) / 0.26f).coerceIn(0f, 1f)

                rider.forEachIndexed { y, row ->
                    val rowNorm = (rider.lastIndex - y) / rider.lastIndex.toFloat() // Reveal wheels before helmet.
                    val isSolid = solidWave > 0f && solidWave >= rowNorm * 0.88f
                    val isSweepLine = (solidWave - rowNorm * 0.88f in 0f..0.12f) && (solidWave < 0.98f)

                    row.forEachIndexed { x, pixel ->
                        palette[pixel]?.let { solidColor ->
                            val px = riderLeft + x
                            val py = riderTop + y

                            if (isSweepLine && solidWave > 0f) {
                                // Brilliant energy transition sweep line
                                block(px, py, 1f, 1f, smokeWhite, 255)
                            } else if (isSolid) {
                                // Full solid pixel
                                block(px, py, 1f, 1f, solidColor, 255)
                            } else {
                                // Luminous aetheric silhouette
                                val sAlpha = (195 * silhouetteAlpha).toInt()
                                val sColor = if ((x + y) % 3 == 0) smokeWhite else smokeIce
                                block(px, py, 1f, 1f, sColor, sAlpha)
                            }
                        }
                    }
                }
            }

            // --- LAYER 6: REFINED ALADDIN LAMP (CRAFTED PIXEL ART) ---
            val lampPulse = if (phase in 0.10f..0.55f) ((sin(phase * 20.0).toFloat() + 1f) * 0.5f) else 0f
            val glowColor = if (lampPulse > 0.5f) goldHi else goldMid

            canvas.save()
            canvas.translate(0f, round(6f * (1f - (phase / 0.12f).coerceIn(0f, 1f))))

            // Stepped base
            block(26f, 63f, 18f, 2f, ink)
            block(27f, 63f, 16f, 1f, goldHi)
            block(27f, 64f, 16f, 1f, goldBronze)
            block(31f, 61f, 7f, 2f, goldDark)
            block(32f, 61f, 5f, 2f, goldMid)

            // Lower belly & shadow
            block(26f, 58f, 17f, 3f, goldDark)
            block(25f, 54f, 19f, 4f, glowColor)
            block(26f, 54f, 16f, 1f, goldHi) // highlight line

            // Shoulder & spout bridge
            block(21f, 51f, 24f, 3f, goldMid)
            block(22f, 51f, 22f, 1f, goldHi) // rim highlight

            // Spout with dark interior opening
            block(18f, 49f, 5f, 2f, goldMid)
            block(16f, 47f, 4f, 2f, goldDark)
            block(14f, 45f, 3f, 2f, goldHi)
            block(13.5f, 45.0f, 1.5f, 1.5f, ink) // Spout orifice: smoke emerges here!

            // Lid & tip jewel
            block(29f, 48f, 11f, 3f, goldMid)
            block(30f, 48f, 9f, 1f, goldHi)
            block(34f, 46f, 3f, 2f, goldHi)
            block(35f, 45f, 1f, 1f, smokeIce) // top jewel gleam

            // Curved handle
            block(45f, 51f, 6f, 2f, goldMid)
            block(49f, 53f, 3f, 5f, goldDark)
            block(44f, 57f, 7f, 2f, goldBronze)
            block(45f, 53f, 4f, 4f, ink) // handle hole cutout

            canvas.restore()

            // --- LAYER 7: CELESTIAL CROSS SPARKLES ---
            if (phase >= 0.70f) {
                val sparkleFade = ((phase - 0.70f) / 0.30f).coerceIn(0f, 1f)
                val sAlpha = (245 * sparkleFade).toInt()

                fun crossSparkle(cx: Float, cy: Float, size: Int, col: Int) {
                    val s = (sin((phase * 18f + cx).toDouble()) * 0.5 + 1.0).toFloat()
                    val a = (sAlpha * s).toInt().coerceIn(0, 255)
                    if (size >= 2) {
                        block(cx - 2f, cy, 5f, 1f, col, a)
                        block(cx, cy - 2f, 1f, 5f, col, a)
                        block(cx - 1f, cy - 1f, 3f, 3f, smokeWhite, a)
                    } else {
                        block(cx - 1f, cy, 3f, 1f, col, a)
                        block(cx, cy - 1f, 1f, 3f, col, a)
                    }
                }
                crossSparkle(12f, 16f, 2, smokeIce)
                crossSparkle(51f, 13f, 2, goldHi)
                crossSparkle(10f, 28f, 1, smokeCyan)
                crossSparkle(53f, 27f, 1, smokeWhite)
                crossSparkle(32f, 42f, 1, goldHi) // sparkle under pedestal cloud
            }

            canvas.restore()
        }
    }
}
