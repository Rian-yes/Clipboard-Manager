package nocom.rian.clipboardmanager.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import nocom.rian.clipboardmanager.R
import nocom.rian.clipboardmanager.db.ClipboardRepository
import nocom.rian.clipboardmanager.ui.OverlayActivity
import kotlinx.coroutines.CoroutineScope
import nocom.rian.clipboardmanager.SettingsActivity
import kotlin.math.hypot

class OverlayWindowManager(
    private val context: Context,
    private val repository: ClipboardRepository, 
    private val scope: CoroutineScope             
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingButton: ImageView? = null
    private var floatParams: WindowManager.LayoutParams? = null

    // Target drop zone elements
    private var removeZoneView: FrameLayout? = null
    private var removeZoneParams: WindowManager.LayoutParams? = null
    private var targetCircle: ImageView? = null 

    // Configuration constants for snapping
    private val snapRadius = 220f 
    private var targetCenterX = 0f
    private var targetCenterY = 0f

    fun setTriggerVisibility(isVisible: Boolean) {
        floatingButton?.let { button ->
            if (button.isAttachedToWindow) {
                button.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
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

        // Initialize the hidden Close Target Area
        createRemoveZoneView(layoutFlag)

        floatingButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private val touchSlop = 12
            private var isDragging = false
            private var isSnapped = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatParams!!.x
                        initialY = floatParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isSnapped = false
                        
                        // Recalculate target positions dynamically based on current layout geometry
                        calculateTargetCenter()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = Math.abs(event.rawX - initialTouchX)
                        val deltaY = Math.abs(event.rawY - initialTouchY)

                        if (!isDragging && (deltaX > touchSlop || deltaY > touchSlop)) {
                            isDragging = true
                            removeZoneView?.visibility = View.VISIBLE
                            
                            // Re-verify center points once layout container passes view inflation frame bounds
                            targetCircle?.post { calculateTargetCenter() }
                        }

                        if (isDragging) {
                            val targetX = initialX + (event.rawX - initialTouchX)
                            val targetY = initialY + (event.rawY - initialTouchY)

                            // Calculate the center point of the floating icon itself
                            val currentButtonCenterX = targetX + (floatingButton!!.width / 2f)
                            val currentButtonCenterY = targetY + (floatingButton!!.height / 2f)

                            // Measure exact distance to the center of the orange target hole
                            val distance = hypot(currentButtonCenterX - targetCenterX, currentButtonCenterY - targetCenterY)

                            if (distance < snapRadius) {
                                // Lock precisely onto the real coordinates of the circle target
                                floatParams!!.x = (targetCenterX - (floatingButton!!.width / 2f)).toInt()
                                floatParams!!.y = (targetCenterY - (floatingButton!!.height / 2f)).toInt()
                                
                                if (!isSnapped) {
                                    isSnapped = true
                                    // Visual feedback: scale up the target circle slightly
                                    targetCircle?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(150)?.start()
                                }
                            } else {
                                // Standard trailing updates tracing user layout paths
                                floatParams!!.x = targetX.toInt()
                                floatParams!!.y = targetY.toInt()
                                
                                if (isSnapped) {
                                    isSnapped = false
                                    // Reset target circle size
                                    targetCircle?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
                                }
                            }
                            windowManager.updateViewLayout(floatingButton, floatParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        removeZoneView?.visibility = View.GONE
                        // Reset target circle scale animation
                        targetCircle?.scaleX = 1.0f
                        targetCircle?.scaleY = 1.0f

                        if (!isDragging) {
                            toggleClipboardDialog()
                        } else {
                            if (isSnapped) {
                                disableOverlaySettingAndClose()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingButton, floatParams)
    }

    /**
     * Builds a clean, centered layout context for the snap-in target structure.
     */
    private fun createRemoveZoneView(layoutFlag: Int) {
        removeZoneView = FrameLayout(context).apply {
            visibility = View.GONE
        }

        // LinearLayout container to structure the circle and text vertically
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val circleSizeDp = 56f 
        val circleSize = (circleSizeDp * context.resources.displayMetrics.density).toInt()

        // Custom Orange Dismiss icon host
        targetCircle = ImageView(context).apply {
            setImageResource(R.drawable.ic_dismiss_circle_orange) 
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                bottomMargin = (12 * context.resources.displayMetrics.density).toInt() 
            }
            alpha = 0.85f 
            scaleType = ImageView.ScaleType.FIT_CENTER 
        }
        container.addView(targetCircle)

        // Informative label below the hole context
        val textView = TextView(context).apply {
            text = "Drag here to dismiss"
            setTextColor(Color.WHITE)
            textSize = 13f
            shadowLayer(3f, 1f, 1f, Color.BLACK) 
        }
        container.addView(textView, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val frameParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = (45 * context.resources.displayMetrics.density).toInt() 
        }
        removeZoneView?.addView(container, frameParams)

        removeZoneParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(removeZoneView, removeZoneParams)
    }

    /**
     * Queries absolute viewport layout location matrices directly from hardware rendering buffers.
     */
    private fun calculateTargetCenter() {
        val view = targetCircle ?: return
        
        // 1. Fetch exact window coordinate arrays mapping directly onto screen display layers
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        
        val viewX = location[0].toFloat()
        val viewY = location[1].toFloat()

        // 2. Add half widths/heights to secure the dead-center point of the orange crosshair circle
        targetCenterX = viewX + (view.width / 2f)
        targetCenterY = viewY + (view.height / 2f)
        
        // Fallback backup if called right at the start frame before views size calculations conclude
        if (viewX == 0f && viewY == 0f) {
            val metrics = context.resources.displayMetrics
            targetCenterX = metrics.widthPixels / 2f
            val centerDp = 45f + 25f + (56f / 2f)
            targetCenterY = metrics.heightPixels - (centerDp * metrics.density)
        }
    }

    private fun disableOverlaySettingAndClose() {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(SettingsActivity.KEY_FLOATING_OVERLAY_ENABLED, false).apply()

        val intent = Intent("nocom.rian.ACTION_TOGGLE_OVERLAY").apply {
            putExtra("enabled", false)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        removeAll()
    }

    private fun toggleClipboardDialog() {
        val intent = Intent(context, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }

    fun removeAll() {
        floatingButton?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
            floatingButton = null
        }
        removeZoneView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
            removeZoneView = null
        }
    }

    private fun TextView.shadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {
        this.setShadowLayer(radius, dx, dy, color)
    }
}
