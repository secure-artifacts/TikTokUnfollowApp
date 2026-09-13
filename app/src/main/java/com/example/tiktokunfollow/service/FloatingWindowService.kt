package com.example.tiktokunfollow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.tiktokunfollow.R
import com.example.tiktokunfollow.model.ExecutionMode
import com.example.tiktokunfollow.model.TargetType
import com.example.tiktokunfollow.model.TaskState

class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "TikTokFloatingServiceChannel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TARGET_TYPE = "EXTRA_TARGET_TYPE"
        const val EXTRA_MODE = "EXTRA_MODE"
        const val EXTRA_SKIP_COUNT = "EXTRA_SKIP_COUNT"
        const val EXTRA_INTERVAL = "EXTRA_INTERVAL"
        const val EXTRA_MAX_LIMIT = "EXTRA_MAX_LIMIT"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var selectedTargetType = TargetType.ALL
    private var selectedMode = ExecutionMode.BOTTOM_FIRST
    private var skipCountN = 10
    private var intervalMs = 2000L
    private var maxUnfollowLimit = 0

    private lateinit var tvStatus: TextView
    private lateinit var tvModeInfo: TextView
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("悬浮控制面板已在后台运行"))
        setupFloatingWindow()
        observeAccessibilityService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val targetTypeOrdinal = it.getIntExtra(EXTRA_TARGET_TYPE, TargetType.ALL.ordinal)
            selectedTargetType = TargetType.values()[targetTypeOrdinal]

            val modeOrdinal = it.getIntExtra(EXTRA_MODE, ExecutionMode.BOTTOM_FIRST.ordinal)
            selectedMode = ExecutionMode.values()[modeOrdinal]

            skipCountN = it.getIntExtra(EXTRA_SKIP_COUNT, 10)
            intervalMs = it.getLongExtra(EXTRA_INTERVAL, 2000L)
            maxUnfollowLimit = it.getIntExtra(EXTRA_MAX_LIMIT, 0)
            updateModeDisplay()
        }
        return START_STICKY
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_control, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        windowManager.addView(floatingView, layoutParams)

        initViewElements()
        setupDragAndDrop()
        setupButtons()
    }

    private fun initViewElements() {
        val root = floatingView ?: return
        tvStatus = root.findViewById(R.id.tv_task_status)
        tvModeInfo = root.findViewById(R.id.tv_mode_info)
        btnStart = root.findViewById(R.id.btn_float_start)
        btnPause = root.findViewById(R.id.btn_float_pause)
        btnStop = root.findViewById(R.id.btn_float_stop)

        val ivClose: ImageView = root.findViewById(R.id.iv_close_floating)
        ivClose.setOnClickListener {
            stopSelf()
        }

        updateModeDisplay()
    }

    private fun updateModeDisplay() {
        if (!::tvModeInfo.isInitialized) return
        val targetStr = when (selectedTargetType) {
            TargetType.ALL -> "全部"
            TargetType.FOLLOWING_ONLY -> "仅已关注"
            TargetType.FRIENDS_ONLY -> "仅好友"
        }
        val modeStr = if (selectedMode == ExecutionMode.SKIP_N_FIRST) {
            "模式1: 跳过最新 $skipCountN 个 ($targetStr)"
        } else {
            "模式2: 底部向上取关 ($targetStr)"
        }
        tvModeInfo.text = modeStr
    }

    /**
     * 拖拽手势：可拖动悬浮窗在屏幕任意位置移动
     */
    private fun setupDragAndDrop() {
        val dragBar = floatingView?.findViewById<View>(R.id.ll_drag_bar) ?: return
        dragBar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 绑定悬浮窗控制按钮点击事件 (开始、暂停、停止)
     */
    private fun setupButtons() {
        btnStart.setOnClickListener {
            val service = TikTokAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "无障碍服务未开启，请先在 App 主界面中开启服务", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (service.currentState == TaskState.PAUSED) {
                service.resumeTask()
            } else {
                service.startTask(selectedMode, skipCountN, intervalMs, maxUnfollowLimit, selectedTargetType)
            }
        }

        btnPause.setOnClickListener {
            val service = TikTokAccessibilityService.instance
            if (service == null) return@setOnClickListener
            service.pauseTask()
        }

        btnStop.setOnClickListener {
            val service = TikTokAccessibilityService.instance
            if (service == null) return@setOnClickListener
            service.stopTask()
        }
    }

    /**
     * 监听 Accessibility 自动化服务的运行状态
     */
    private fun observeAccessibilityService() {
        TikTokAccessibilityService.instance?.onStatusChangeListener = { state, message ->
            floatingView?.post {
                tvStatus.text = "状态: $message"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) {
            windowManager.removeView(floatingView)
            floatingView = null
        }
        TikTokAccessibilityService.instance?.stopTask()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TikTok 悬浮窗控制服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok 自动取关助手")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
