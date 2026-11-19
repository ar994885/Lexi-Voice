package com.example.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.LexiVoiceApplication
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlaySentenceTv: TextView? = null
    private var overlayMinimizeBtn: ImageButton? = null
    private var overlayCloseBtn: ImageButton? = null
    private var overlayPrevBtn: ImageButton? = null
    private var overlayPlayBtn: ImageButton? = null
    private var overlayNextBtn: ImageButton? = null
    private var overlayExpandedContainer: LinearLayout? = null
    private var overlayBubbleContainer: LinearLayout? = null
    private var overlayBubbleIcon: ImageView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var savedExpandedX: Int = 0
    private var savedExpandedY: Int = 0
    private var savedBubbleX: Int = 0
    private var savedBubbleY: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeSystemOverlay()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW, null -> {
                // The overlay permission can be revoked at any time from Settings.
                // Bail out cleanly rather than crashing on addView().
                if (!Settings.canDrawOverlays(this)) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Foreground from the first moment: as a plain started service this
                // was stopped within minutes once the app left the foreground, which
                // is exactly when the overlay is needed.
                startAsForeground()
                if (overlayView == null) {
                    createCompactSystemOverlayView()
                }
                startObservingState()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Promotes the service to foreground so the overlay survives while the user
     * is in another app or the screen is off.
     *
     * specialUse is the correct foreground service type for a persistent overlay
     * on API 34+, and it is declared in the manifest together with its subtype
     * property. The notification is minimal-importance, ongoing, and carries a
     * single "Stop overlay" action.
     */
    private fun startAsForeground() {
        val notification = buildOverlayNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Some OEM ROMs reject the type; fall back to an untyped foreground
            // service rather than losing the overlay entirely.
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun buildOverlayNotification(): Notification {
        val stopIntent = Intent(this, FloatingOverlayService::class.java).setAction(ACTION_HIDE)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_speaker_wave)
            .setContentTitle("LexiVoice overlay active")
            .setContentText("Your floating reader is available over other apps")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(0, "Stop overlay", stopPendingIntent)
            .build()
    }

    private fun startObservingState() {
        if (collectorJob?.isActive == true) return
        collectorJob = serviceScope.launch {
            FloatingOverlayController.state.collect { st ->
                if (!st.isVisible || !Settings.canDrawOverlays(this@FloatingOverlayService)) {
                    removeSystemOverlay()
                    ServiceCompat.stopForeground(
                        this@FloatingOverlayService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                } else {
                    if (overlayView == null) {
                        createCompactSystemOverlayView()
                    }
                    updateOverlayContent(st)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCompactSystemOverlayView() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(8).toFloat()
        }

        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sentenceTv = TextView(this).apply {
            setTextColor(Color.parseColor("#F5F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            contentDescription = "Active sentence. Tap to jump to reader."
        }

        val minimizeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_dock_hide)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Minimize to floating bubble"
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener {
                FloatingOverlayController.setTemporarilyHidden(true)
            }
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close_small)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Close overlay"
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener {
                FloatingOverlayController.setVisible(this@FloatingOverlayService, false)
            }
        }

        topRow.addView(sentenceTv)
        topRow.addView(minimizeBtn)
        topRow.addView(closeBtn)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        val prevBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_skip_previous)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Previous sentence"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(18)
            }
            setOnClickListener { FloatingOverlayController.skipPrevious(this@FloatingOverlayService) }
        }

        val playBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play_arrow)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D97736"))
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Play or pause"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { FloatingOverlayController.togglePlayPause(this@FloatingOverlayService) }
        }

        val nextBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_skip_next)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Next sentence"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(18)
            }
            setOnClickListener { FloatingOverlayController.skipNext(this@FloatingOverlayService) }
        }

        controlsRow.addView(prevBtn)
        controlsRow.addView(playBtn)
        controlsRow.addView(nextBtn)

        expanded.addView(topRow)
        expanded.addView(controlsRow)

        // Compact 48dp x 48dp Circular Floating Bubble
        val bubbleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_speaker_wave)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        val bubbleContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = "Expand floating audio bar"
            addView(bubbleIcon)
        }

        root.addView(expanded)
        root.addView(bubbleContainer)

        savedExpandedX = dp(24)
        savedExpandedY = dp(120)
        savedBubbleX = dp(24)
        savedBubbleY = dp(200)

        val params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedExpandedX
            y = savedExpandedY
        }

        // Expanded sentence drag + tap-to-jump
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false
        sentenceTv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initX + dx.toInt()
                        params.y = initY + dy.toInt()
                        savedExpandedX = params.x
                        savedExpandedY = params.y
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val jumpIntent = FloatingOverlayController.buildDeepLinkIntent(
                            this@FloatingOverlayService
                        )
                        runCatching { startActivity(jumpIntent) }
                    }
                    true
                }
                else -> false
            }
        }

        // Circular bubble drag + tap-to-expand
        var bInitX = 0
        var bInitY = 0
        var bTouchX = 0f
        var bTouchY = 0f
        var bDragging = false
        bubbleContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bInitX = params.x
                    bInitY = params.y
                    bTouchX = event.rawX
                    bTouchY = event.rawY
                    bDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - bTouchX
                    val dy = event.rawY - bTouchY
                    if (!bDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        bDragging = true
                    }
                    if (bDragging) {
                        params.x = bInitX + dx.toInt()
                        params.y = bInitY + dy.toInt()
                        savedBubbleX = params.x
                        savedBubbleY = params.y
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!bDragging) {
                        FloatingOverlayController.setTemporarilyHidden(false)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(root, params)
            overlayView = root
            overlaySentenceTv = sentenceTv
            overlayMinimizeBtn = minimizeBtn
            overlayCloseBtn = closeBtn
            overlayPrevBtn = prevBtn
            overlayPlayBtn = playBtn
            overlayNextBtn = nextBtn
            overlayExpandedContainer = expanded
            overlayBubbleContainer = bubbleContainer
            overlayBubbleIcon = bubbleIcon
            overlayParams = params
        }
    }

    private fun updateOverlayContent(st: FloatingOverlayState) {
        val root = overlayView ?: return
        val wm = windowManager ?: return
        val params = overlayParams ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val colors = OverlayStyleHelper.resolveColors(st.appearanceSettings)

        if (st.isTemporarilyHidden) {
            overlayExpandedContainer?.visibility = View.GONE
            overlayBubbleContainer?.visibility = View.VISIBLE

            root.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors.backgroundArgb)
                if (colors.borderWidthDp > 0) {
                    setStroke(dp(colors.borderWidthDp), colors.borderArgb)
                }
            }
            overlayBubbleIcon?.setImageResource(
                if (st.isPlaying) R.drawable.ic_speaker_wave else R.drawable.ic_play_arrow
            )
            overlayBubbleIcon?.setColorFilter(colors.primaryTextArgb, PorterDuff.Mode.SRC_IN)

            params.gravity = Gravity.TOP or Gravity.START
            params.x = savedBubbleX
            params.y = savedBubbleY
            params.width = dp(48)
            params.height = dp(48)
        } else {
            overlayExpandedContainer?.visibility = View.VISIBLE
            overlayBubbleContainer?.visibility = View.GONE

            root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(colors.backgroundArgb)
                if (colors.borderWidthDp > 0) {
                    setStroke(dp(colors.borderWidthDp), colors.borderArgb)
                }
            }

            overlaySentenceTv?.apply {
                text = st.displayText
                setTextColor(colors.primaryTextArgb)
            }
            overlayMinimizeBtn?.setColorFilter(colors.secondaryIconArgb, PorterDuff.Mode.SRC_IN)
            overlayCloseBtn?.setColorFilter(colors.secondaryIconArgb, PorterDuff.Mode.SRC_IN)
            overlayPrevBtn?.setColorFilter(colors.primaryTextArgb, PorterDuff.Mode.SRC_IN)
            overlayNextBtn?.setColorFilter(colors.primaryTextArgb, PorterDuff.Mode.SRC_IN)
            overlayPlayBtn?.setImageResource(
                if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = savedExpandedX
            params.y = savedExpandedY
            params.width = dp(260)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun removeSystemOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        overlayView = null
        overlaySentenceTv = null
        overlayMinimizeBtn = null
        overlayCloseBtn = null
        overlayPrevBtn = null
        overlayPlayBtn = null
        overlayNextBtn = null
        overlayExpandedContainer = null
        overlayBubbleContainer = null
        overlayBubbleIcon = null
        overlayParams = null
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        removeSystemOverlay()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4501

        const val ACTION_SHOW = "com.example.overlay.SHOW"
        const val ACTION_HIDE = "com.example.overlay.HIDE"
    }
}
