package nocom.rian.clipboardmanager.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.ImageView
import nocom.rian.clipboardmanager.R
import nocom.rian.clipboardmanager.db.ClipboardRepository
import nocom.rian.clipboardmanager.ui.OverlayActivity
import kotlinx.coroutines.CoroutineScope

class OverlayWindowManager(
    private val context: Context,
    private val repository: ClipboardRepository, // Maintained for ClipboardService constructor matching
    private val scope: CoroutineScope             // Maintained for ClipboardService constructor matching
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingButton: ImageView? = null
    private var floatParams: WindowManager.LayoutParams? = null

    fun showFloatingTrigger() {
        if (floatingButton != null) return

        floatingButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_clipboard)
            setBackgroundResource(android.R.drawable.toast_frame)
            setPadding(28, 28, 28, 28)
            alpha = 0.85f
            elevation = 10f
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        floatParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 600
        }

        floatingButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private val touchSlop = 12

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatParams!!.x
                        initialY = floatParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        floatParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        floatParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingButton, floatParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = Math.abs(event.rawX - initialTouchX)
                        val deltaY = Math.abs(event.rawY - initialTouchY)
                        if (deltaX < touchSlop && deltaY < touchSlop) {
                            toggleClipboardDialog()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingButton, floatParams)
    }

    private fun toggleClipboardDialog() {
        val intent = Intent(context, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
    
    fun removeAll() {
        floatingButton?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
            floatingButton = null
        }
    }
}
