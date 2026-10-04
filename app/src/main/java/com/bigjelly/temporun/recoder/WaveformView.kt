package com.bigjelly.temporun.recoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * 用最近一段时间的振幅样本绘制简单的实时波形。
 *
 * 它不负责产生或保存音频，只是一个显示组件。PcmRecoder 每次读取一批 PCM 后计算
 * 峰值，再通过 addAmplitude() 传给这里；View 根据这些峰值绘制上下对称的填充图形。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 保存最近若干批音频的峰值，值域为 0.0 到 1.0。 */
    private val amplitudes = ArrayList<Float>(600)
    /** 最多保留的波形点数，超出后丢弃最早的数据。 */
    private val maxPointCount = 600

    /** 绘制波形填充区域。 */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(55, 178, 255)
    }

    /** 绘制中间基线。 */
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = Color.rgb(90, 100, 110)
    }

    /** 波形路径会反复复用，避免每次重绘都创建新对象。 */
    fun addAmplitude(amplitude: Float) {
        // View 的数据修改必须在主线程进行；录音回调通常来自后台线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { addAmplitude(amplitude) }
            return
        }
        amplitudes.add(amplitude.coerceIn(0f, 1f))
        if (amplitudes.size > maxPointCount) {
            // 只显示最近的数据，从而形成向左滚动的实时波形效果。
            amplitudes.removeAt(0)
        }
        // 通知 Android 在下一帧调用 onDraw()。
        invalidate()
    }

    fun clearWaveform() {
        // 清空同样必须在主线程执行。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { clearWaveform() }
            return
        }
        amplitudes.clear()
        invalidate()
    }

    /** 绘制波形时复用的路径对象。 */
    var path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f

        // 先绘制背景和中心基线；振幅以中心线为基准向上下展开。
        canvas.drawColor(Color.rgb(18, 22, 27))
        canvas.drawLine(0f, centerY, width, centerY, baselinePaint)

        if (amplitudes.isEmpty() || width <= 0f || height <= 0f) return

        path.reset()
        val lastIndex = amplitudes.lastIndex
        // 将样本索引均匀映射到 View 的宽度。
        val xStep = if (lastIndex == 0) 0f else width / lastIndex

        for (index in amplitudes.indices) {
            val x = if (lastIndex == 0) width / 2f else index * xStep
            // 0.45 用来保留上下边距，避免最大振幅贴住 View 边缘。
            val halfHeight = amplitudes[index] * height * 0.45f
            val y = centerY - halfHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // 沿底部反向连接，闭合成一个可填充的上下对称区域。
        for (index in lastIndex downTo 0) {
            val x = if (lastIndex == 0) width / 2f else index * xStep
            val halfHeight = amplitudes[index] * height * 0.45f
            path.lineTo(x, centerY + halfHeight)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
    }
}
