package com.example.tiktokunfollow

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tiktokunfollow.model.ExecutionMode
import com.example.tiktokunfollow.model.TargetType
import com.example.tiktokunfollow.service.FloatingWindowService
import com.example.tiktokunfollow.service.TikTokAccessibilityService
import com.example.tiktokunfollow.utils.PermissionHelper
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnGrantOverlay: Button
    private lateinit var rgTargetType: RadioGroup
    private lateinit var rbTargetAll: RadioButton
    private lateinit var rbTargetFollowing: RadioButton
    private lateinit var rbTargetFriends: RadioButton
    private lateinit var rgModes: RadioGroup
    private lateinit var rbModeBottom: RadioButton
    private lateinit var rbModeSkip: RadioButton
    private lateinit var etSkipCount: TextInputEditText
    private lateinit var etInterval: TextInputEditText
    private lateinit var etMaxLimit: TextInputEditText
    private lateinit var btnLaunchFloating: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun initViews() {
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)

        rgTargetType = findViewById(R.id.rg_target_type)
        rbTargetAll = findViewById(R.id.rb_target_all)
        rbTargetFollowing = findViewById(R.id.rb_target_following)
        rbTargetFriends = findViewById(R.id.rb_target_friends)

        rgModes = findViewById(R.id.rg_modes)
        rbModeBottom = findViewById(R.id.rb_mode_bottom)
        rbModeSkip = findViewById(R.id.rb_mode_skip)
        etSkipCount = findViewById(R.id.et_skip_count)
        etInterval = findViewById(R.id.et_interval)
        etMaxLimit = findViewById(R.id.et_max_limit)

        btnLaunchFloating = findViewById(R.id.btn_launch_floating)
    }

    private fun setupListeners() {
        btnGrantAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        btnGrantOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }

        btnLaunchFloating.setOnClickListener {
            if (!PermissionHelper.isOverlayPermissionGranted(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!PermissionHelper.isAccessibilityServiceEnabled(this, TikTokAccessibilityService::class.java)) {
                Toast.makeText(this, "请先在无障碍设置中开启【TikTok 自动取关无障碍服务】！", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            launchFloatingService()
        }
    }

    private fun updatePermissionStatuses() {
        val isAccEnabled = PermissionHelper.isAccessibilityServiceEnabled(
            this, TikTokAccessibilityService::class.java
        )
        if (isAccEnabled) {
            tvAccessibilityStatus.text = "无障碍服务：已开启"
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_green))
            btnGrantAccessibility.text = "已开启"
            btnGrantAccessibility.isEnabled = false
        } else {
            tvAccessibilityStatus.text = "无障碍服务：未开启"
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_red))
            btnGrantAccessibility.text = "去开启"
            btnGrantAccessibility.isEnabled = true
        }

        val isOverlayEnabled = PermissionHelper.isOverlayPermissionGranted(this)
        if (isOverlayEnabled) {
            tvOverlayStatus.text = "悬浮窗权限：已开启"
            tvOverlayStatus.setTextColor(getColor(R.color.accent_green))
            btnGrantOverlay.text = "已授权"
            btnGrantOverlay.isEnabled = false
        } else {
            tvOverlayStatus.text = "悬浮窗权限：未开启"
            tvOverlayStatus.setTextColor(getColor(R.color.accent_red))
            btnGrantOverlay.text = "去授权"
            btnGrantOverlay.isEnabled = true
        }
    }

    private fun launchFloatingService() {
        val targetType = when {
            rbTargetFollowing.isChecked -> TargetType.FOLLOWING_ONLY
            rbTargetFriends.isChecked -> TargetType.FRIENDS_ONLY
            else -> TargetType.ALL
        }

        val mode = if (rbModeSkip.isChecked) {
            ExecutionMode.SKIP_N_FIRST
        } else {
            ExecutionMode.BOTTOM_FIRST
        }

        val skipCount = etSkipCount.text.toString().toIntOrNull() ?: 10
        val interval = etInterval.text.toString().toLongOrNull() ?: 2000L
        val maxLimit = etMaxLimit.text.toString().toIntOrNull() ?: 0

        val intent = Intent(this, FloatingWindowService::class.java).apply {
            putExtra(FloatingWindowService.EXTRA_TARGET_TYPE, targetType.ordinal)
            putExtra(FloatingWindowService.EXTRA_MODE, mode.ordinal)
            putExtra(FloatingWindowService.EXTRA_SKIP_COUNT, skipCount)
            putExtra(FloatingWindowService.EXTRA_INTERVAL, interval)
            putExtra(FloatingWindowService.EXTRA_MAX_LIMIT, maxLimit)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "悬浮面板已开启，请切换至 TikTok/抖音 应用中使用！", Toast.LENGTH_LONG).show()
    }
}
