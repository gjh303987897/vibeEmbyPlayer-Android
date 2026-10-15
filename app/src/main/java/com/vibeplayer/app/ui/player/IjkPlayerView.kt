package com.vibeplayer.app.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.vibeplayer.app.player.BitmapSubtitleFrame
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/** Native video surface for the application-scoped IJKPlayer instance. */
class IjkPlayerView(
    context: Context,
    private val onVideoSurfaceChanged: (SurfaceHolder, Boolean) -> Unit,
    attrs: android.util.AttributeSet? = null
) : FrameLayout(context, attrs), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private val bitmapSubtitleView = BitmapSubtitleView(context)

    var bitmapSubtitleFrame: BitmapSubtitleFrame?
        get() = bitmapSubtitleView.frame
        set(value) {
            if (bitmapSubtitleView.frame === value) return
            bitmapSubtitleView.frame = value
            bitmapSubtitleView.invalidate()
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        addView(bitmapSubtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(this)
    }

    var player: IjkMediaPlayer? = null
    private var videoAspectRatio = 0.0

    fun updateVideoSize() {
        val current = player ?: return
        val width = current.videoWidth
        val height = current.videoHeight
        if (width <= 0 || height <= 0) return
        val sarNum = current.videoSarNum
        val sarDen = current.videoSarDen
        val aspect = width.toDouble() / height * if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
        if (aspect != videoAspectRatio) {
            videoAspectRatio = aspect
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoAspectRatio <= 0.0 || measuredWidth <= 0 || measuredHeight <= 0) return
        val width: Int
        val height: Int
        if (measuredWidth.toDouble() / measuredHeight > videoAspectRatio) {
            height = measuredHeight
            width = (height * videoAspectRatio).toInt()
        } else {
            width = measuredWidth
            height = (width / videoAspectRatio).toInt()
        }
        surfaceView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Use the laid-out SurfaceView, including integer rounding, SAR and letterboxing.
        bitmapSubtitleView.setVideoBounds(
            surfaceView.left - bitmapSubtitleView.left,
            surfaceView.top - bitmapSubtitleView.top,
            surfaceView.right - bitmapSubtitleView.left,
            surfaceView.bottom - bitmapSubtitleView.top
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        onVideoSurfaceChanged(holder, true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onVideoSurfaceChanged(holder, false)
    }

    override fun onDetachedFromWindow() {
        onVideoSurfaceChanged(surfaceView.holder, false)
        super.onDetachedFromWindow()
    }
}

/** Draws in subtitle canvas coordinates without allocating or copying pixels during draw. */
private class BitmapSubtitleView(context: Context) : View(context) {
    var frame: BitmapSubtitleFrame? = null
    private val videoBounds = RectF()
    private val regionBounds = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setVideoBounds(left: Int, top: Int, right: Int, bottom: Int) {
        if (videoBounds.left == left.toFloat() && videoBounds.top == top.toFloat() &&
            videoBounds.right == right.toFloat() && videoBounds.bottom == bottom.toFloat()) return
        videoBounds.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        if (current.canvasWidth <= 0 || current.canvasHeight <= 0 || videoBounds.isEmpty) return
        val scaleX = videoBounds.width() / current.canvasWidth
        val scaleY = videoBounds.height() / current.canvasHeight
        val checkpoint = canvas.save()
        canvas.clipRect(videoBounds)
        for (region in current.regions) {
            regionBounds.set(
                videoBounds.left + region.x * scaleX,
                videoBounds.top + region.y * scaleY,
                videoBounds.left + (region.x.toFloat() + region.bitmap.width) * scaleX,
                videoBounds.top + (region.y.toFloat() + region.bitmap.height) * scaleY
            )
            // Explicit destination bounds avoid density scaling of the native pixel bitmap.
            canvas.drawBitmap(region.bitmap, null, regionBounds, bitmapPaint)
        }
        canvas.restoreToCount(checkpoint)
    }
}
