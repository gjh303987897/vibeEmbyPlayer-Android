package com.vibeplayer.app.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.vibeplayer.app.player.BitmapSubtitleFrame
import com.vibeplayer.app.player.VideoScaleMode
import com.vibeplayer.app.player.VideoPresentation
import com.vibeplayer.app.player.normalizeVideoZoom
import com.vibeplayer.app.player.videoPresentation
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/** Native video surface for the application-scoped IJKPlayer instance. */
class IjkPlayerView(
    context: Context,
    private val onVideoSurfaceChanged: (SurfaceHolder, Boolean) -> Unit,
    attrs: android.util.AttributeSet? = null
) : FrameLayout(context, attrs), SurfaceHolder.Callback {
    private var surfaceView = SurfaceView(context)
    private var surfaceGeneration = 0L
    private val bitmapSubtitleView = BitmapSubtitleView(context)
    // Keep the actual Surface attached: hiding/recreating it would restart codecs
    // and reintroduce the seek audio recovery race.
    private val seekShutter = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    var videoOutputBlocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            seekShutter.visibility = if (value) VISIBLE else GONE
        }
    private val visibleVideoBounds = Rect()
    var onVideoBoundsChanged: ((Rect) -> Unit)? = null

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
        addView(seekShutter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(this)
    }

    var player: IjkMediaPlayer? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoSarNum = 1
    private var videoSarDen = 1
    private var scaleMode = VideoScaleMode.FIT
    private var zoom = 1f
    private var presentation = VideoPresentation(1, 1, 1f)

    fun updatePresentation(mode: VideoScaleMode, zoomScale: Float) {
        val safeZoom = normalizeVideoZoom(zoomScale)
        if (scaleMode == mode && zoom == safeZoom) return
        scaleMode = mode
        zoom = safeZoom
        requestLayout()
    }

    /** A Surface used by CPU rendering cannot later be connected to MediaCodec. */
    fun updateSurfaceGeneration(generation: Long) {
        if (surfaceGeneration == generation) return
        surfaceGeneration = generation
        removeView(surfaceView)
        surfaceView.holder.removeCallback(this)
        surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(this)
        addView(surfaceView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    fun updateVideoSize() {
        val current = player ?: return
        val width = current.videoWidth
        val height = current.videoHeight
        if (width <= 0 || height <= 0) return
        val sarNum = current.videoSarNum
        val sarDen = current.videoSarDen
        if (width != videoWidth || height != videoHeight || sarNum != videoSarNum || sarDen != videoSarDen) {
            videoWidth = width
            videoHeight = height
            videoSarNum = sarNum
            videoSarDen = sarDen
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        presentation = videoPresentation(measuredWidth, measuredHeight, videoWidth, videoHeight,
            videoSarNum, videoSarDen, scaleMode, zoom)
        surfaceView.measure(MeasureSpec.makeMeasureSpec(presentation.surfaceWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(presentation.surfaceHeight, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        surfaceView.pivotX = surfaceView.width / 2f
        surfaceView.pivotY = surfaceView.height / 2f
        surfaceView.scaleX = presentation.scale
        surfaceView.scaleY = presentation.scale
        // Match bitmap coordinates to the transformed video, including SAR and cropping.
        val centerX = (surfaceView.left + surfaceView.right) / 2f - bitmapSubtitleView.left
        val centerY = (surfaceView.top + surfaceView.bottom) / 2f - bitmapSubtitleView.top
        val halfWidth = surfaceView.width * presentation.scale / 2f
        val halfHeight = surfaceView.height * presentation.scale / 2f
        bitmapSubtitleView.setVideoBounds(
            centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight
        )
        if (surfaceView.getGlobalVisibleRect(visibleVideoBounds)) onVideoBoundsChanged?.invoke(visibleVideoBounds)
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

    fun setVideoBounds(left: Float, top: Float, right: Float, bottom: Float) {
        if (videoBounds.left == left && videoBounds.top == top &&
            videoBounds.right == right && videoBounds.bottom == bottom) return
        videoBounds.set(left, top, right, bottom)
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
