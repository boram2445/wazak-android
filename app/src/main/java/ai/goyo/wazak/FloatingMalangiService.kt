package ai.goyo.wazak

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import java.io.File
import kotlin.math.abs

class FloatingMalangiService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var repository: MalangiRepository
    private val player = SoundPlayer()
    private var malangiView: PressableMalangiView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        repository = MalangiRepository(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        malangiView?.setImagePath(repository.selected()?.imagePath)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        malangiView?.let { windowManager.removeView(it) }
        player.release()
        malangiView = null
        layoutParams = null
        super.onDestroy()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this) || malangiView != null) return

        val view = PressableMalangiView(this).apply {
            setImagePath(repository.selected()?.imagePath)
            layoutParams = ViewGroup.LayoutParams(220, 220)
        }

        val params = WindowManager.LayoutParams(
            220,
            220,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = repository.overlayX
            y = repository.overlayY
        }

        attachTouchHandling(view, params)
        windowManager.addView(view, params)
        malangiView = view
        layoutParams = params
    }

    private fun attachTouchHandling(view: PressableMalangiView, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    view.setPressedPoint(event.x, event.y, 1f)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 8f || abs(dy) > 8f) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.releasePress()
                    repository.overlayX = params.x
                    repository.overlayY = params.y
                    if (!moved) {
                        repository.selected()?.soundPaths?.randomOrNull()?.let { player.play(it) }
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.releasePress()
                    true
                }

                else -> false
            }
        }
    }
}
