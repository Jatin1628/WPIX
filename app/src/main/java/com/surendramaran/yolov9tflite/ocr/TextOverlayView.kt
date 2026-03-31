package com.surendramaran.yolov9tflite.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.surendramaran.yolov9tflite.R

class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var textBlocks: List<TextBlock> = emptyList()
    private val boxPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.orange)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.orange)
        textSize = 14f
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.black)
        style = Paint.Style.FILL
        alpha = 200
    }

    fun setTextBlocks(blocks: List<TextBlock>) {
        this.textBlocks = blocks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (block in textBlocks) {
            block.boundingBox?.let { rect ->
                // Draw bounding box
                canvas.drawRect(
                    rect.left.toFloat(),
                    rect.top.toFloat(),
                    rect.right.toFloat(),
                    rect.bottom.toFloat(),
                    boxPaint
                )

                // Draw text label above the box
                val textWidth = textPaint.measureText(block.text)
                val textHeight = textPaint.fontMetrics.run { descent - ascent }

                val labelRect = Rect(
                    rect.left,
                    (rect.top - textHeight - 8).toInt(),
                    (rect.left + textWidth + 8).toInt(),
                    rect.top
                )

                canvas.drawRect(labelRect, backgroundPaint)
                canvas.drawText(
                    block.text,
                    (rect.left + 4).toFloat(),
                    (rect.top - 4).toFloat(),
                    textPaint
                )
            }
        }
    }
}
