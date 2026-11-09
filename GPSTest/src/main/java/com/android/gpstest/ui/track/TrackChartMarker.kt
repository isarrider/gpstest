/*
 * Copyright (C) 2026 The GPSTest contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.gpstest.ui.track

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import com.github.mikephil.charting.components.IMarker
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * The window shown above the selected point of a Track screen chart, while the finger is on the
 * chart: the x value (time or distance) and the y value, each with its unit.
 *
 * Drawn directly on the chart's canvas, and kept inside the chart: it moves below the point near
 * the top and stays clear of the left and right edges.
 */
class TrackChartMarker(
    context: Context,
    private val xText: (Float) -> String,
    private val yText: (Float) -> String,
    textColor: Int,
    backgroundColor: Int
) : IMarker {

    private val density = context.resources.displayMetrics.density
    private val padding = 6 * density
    private val gap = 8 * density
    private val corner = 4 * density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            13f,
            context.resources.displayMetrics
        )
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 60
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    private var lines: List<String> = emptyList()
    private val box = RectF()
    private val offset = MPPointF()

    override fun refreshContent(e: Entry, highlight: Highlight) {
        lines = listOf(xText(e.x), yText(e.y))
    }

    override fun getOffset(): MPPointF = offset

    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF = offset

    override fun draw(canvas: Canvas, posX: Float, posY: Float) {
        if (lines.isEmpty()) return
        val lineHeight = textPaint.fontSpacing
        val width = lines.maxOf { textPaint.measureText(it) } + 2 * padding
        val height = lines.size * lineHeight + 2 * padding

        // Centered above the point, below it if there's no room above, never off the chart
        val left = (posX - width / 2).coerceIn(0f, (canvas.width - width).coerceAtLeast(0f))
        val top = if (posY - gap - height >= 0) posY - gap - height else posY + gap
        box.set(left, top, left + width, top + height)

        canvas.drawRoundRect(box, corner, corner, backgroundPaint)
        canvas.drawRoundRect(box, corner, corner, borderPaint)
        var baseline = top + padding - textPaint.fontMetrics.ascent
        lines.forEach {
            canvas.drawText(it, left + padding, baseline, textPaint)
            baseline += lineHeight
        }
    }
}
