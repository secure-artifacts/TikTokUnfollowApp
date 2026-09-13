package com.example.tiktokunfollow.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.tiktokunfollow.model.ExecutionMode
import com.example.tiktokunfollow.model.TargetType
import com.example.tiktokunfollow.model.TaskState
import com.example.tiktokunfollow.model.UnfollowTaskConfig
import kotlin.random.Random

class TikTokAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "TikTokUnfollowService"
        var instance: TikTokAccessibilityService? = null
            private set
    }

    // 全局任务配置与当前状态
    val config = UnfollowTaskConfig()
    var currentState = TaskState.IDLE
        private set

    // 状态回调接口，向悬浮窗通知状态变更
    var onStatusChangeListener: ((state: TaskState, message: String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentSkippedCount = 0
    private var bottomCheckSameCount = 0
    private var lastNodeHash = 0
    private var totalScrollBottomSwipes = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "TikTokAccessibilityService 已连接并就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 自动化任务主循环由 Handler 定时器驱动
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
        stopTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopTask()
    }

    /**
     * 开始任务
     */
    fun startTask(mode: ExecutionMode, skipN: Int, intervalMs: Long, maxLimit: Int = 0, targetType: TargetType = TargetType.ALL) {
        config.mode = mode
        config.skipCount = skipN
        config.intervalMs = intervalMs
        config.maxUnfollowLimit = maxLimit
        config.targetType = targetType
        config.unfollowedCount = 0
        currentSkippedCount = 0
        bottomCheckSameCount = 0
        lastNodeHash = 0
        totalScrollBottomSwipes = 0

        when (mode) {
            ExecutionMode.SKIP_N_FIRST -> {
                updateState(TaskState.SKIPPING_OFFSET, "正在跳过最新 $skipN 个/滑动 $skipN 次...")
            }
            ExecutionMode.BOTTOM_FIRST -> {
                updateState(TaskState.SCROLLING_TO_BOTTOM, "正在快速向下滑动至最底部...")
            }
        }

        handler.removeCallbacks(autoTaskRunnable)
        handler.post(autoTaskRunnable)
    }

    /**
     * 暂停任务
     */
    fun pauseTask() {
        updateState(TaskState.PAUSED, "任务已暂停")
        handler.removeCallbacks(autoTaskRunnable)
    }

    /**
     * 恢复/继续任务
     */
    fun resumeTask() {
        if (currentState == TaskState.PAUSED) {
            updateState(TaskState.UNFOLLOWING, "正在执行取关操作...")
            handler.removeCallbacks(autoTaskRunnable)
            handler.post(autoTaskRunnable)
        }
    }

    /**
     * 停止任务
     */
    fun stopTask() {
        updateState(TaskState.STOPPED, "任务已停止")
        handler.removeCallbacks(autoTaskRunnable)
        currentSkippedCount = 0
        totalScrollBottomSwipes = 0
    }

    private fun updateState(newState: TaskState, message: String) {
        currentState = newState
        onStatusChangeListener?.invoke(newState, message)
        Log.d(TAG, "State -> $newState: $message")
        if (newState == TaskState.STOPPED && (message.contains("完成") || message.contains("达成"))) {
            playCompletionAlert()
        }
    }

    /**
     * 任务完成时播放系统提示音与脉冲震动提醒
     */
    private fun playCompletionAlert() {
        try {
            // 触发手机双重脉冲震动 (300ms + 150ms + 300ms)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 300, 150, 300)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500)
            }

            // 播放系统默认完成通知提示音
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "任务完成提示音/震动触发失败", e)
        }
    }

    /**
     * 递归收集屏幕上所有可视 View 的文本以进行准确的界面变动 Hash 校验
     */
    private fun collectScreenTexts(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val t = node.text
        if (!t.isNullOrEmpty()) {
            sb.append(t).append("|")
        }
        val cd = node.contentDescription
        if (!cd.isNullOrEmpty()) {
            sb.append(cd).append("|")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenTexts(child, sb)
        }
    }

    private fun getScreenContentHash(): Int {
        val root = rootInActiveWindow ?: return 0
        val sb = StringBuilder()
        collectScreenTexts(root, sb)
        return sb.toString().hashCode()
    }

    /**
     * 核心自动化逻辑定时器
     */
    private val autoTaskRunnable: Runnable = object : Runnable {
        override fun run() {
            if (currentState == TaskState.PAUSED || currentState == TaskState.STOPPED || currentState == TaskState.IDLE) {
                return
            }

            try {
                when (currentState) {
                    TaskState.SCROLLING_TO_BOTTOM -> {
                        executeScrollToBottomStep()
                    }
                    TaskState.SKIPPING_OFFSET -> {
                        executeSkipStep()
                    }
                    TaskState.UNFOLLOWING -> {
                        executeUnfollowStep()
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动化步骤执行异常", e)
            }

            // 在翻页/下滑模式中使用 650ms 快速连续滑动；在取关点击时使用基础配置延时
            val nextDelay = when (currentState) {
                TaskState.SCROLLING_TO_BOTTOM, TaskState.SKIPPING_OFFSET -> 650L
                else -> config.intervalMs + Random.nextLong(100, 500)
            }
            handler.postDelayed(this, nextDelay)
        }
    }

    /**
     * 步骤：翻到最底部
     */
    private fun executeScrollToBottomStep() {
        val currentHash = getScreenContentHash()

        if (currentHash != 0 && currentHash == lastNodeHash) {
            bottomCheckSameCount++
        } else {
            bottomCheckSameCount = 0
            if (currentHash != 0) {
                lastNodeHash = currentHash
            }
        }

        // 至少连续滑动 3 次以上，且连续 4 次检测到屏幕文字内容完全没有变化，才判定真正触底
        if (totalScrollBottomSwipes >= 3 && bottomCheckSameCount >= 4) {
            updateState(TaskState.UNFOLLOWING, "已到达最底部！开始自下而上批量取关...")
            return
        }

        // 向上滑动屏幕（列表向下滑动）
        performSwipeUp()
        totalScrollBottomSwipes++
        updateState(TaskState.SCROLLING_TO_BOTTOM, "正在向下翻页: 已翻页 $totalScrollBottomSwipes 次...")
    }

    /**
     * 步骤：跳过指定的 N 次/数量
     */
    private fun executeSkipStep() {
        if (currentSkippedCount >= config.skipCount) {
            updateState(TaskState.UNFOLLOWING, "已成功跳过 $currentSkippedCount 次，开始取关！")
            return
        }

        performSwipeUp()
        currentSkippedCount++
        updateState(TaskState.SKIPPING_OFFSET, "正在翻页/跳过: $currentSkippedCount / ${config.skipCount}")
    }

    /**
     * 步骤：寻找并点击“已关注”/“Following”/“好友”按钮进行取关
     */
    private fun executeUnfollowStep() {
        if (config.maxUnfollowLimit > 0 && config.unfollowedCount >= config.maxUnfollowLimit) {
            stopTask()
            updateState(TaskState.STOPPED, "已达成目标取关上限 (${config.maxUnfollowLimit}人)，任务自动结束！")
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            updateState(TaskState.UNFOLLOWING, "等待 TikTok 界面数据...")
            return
        }

        // 寻找符合条件取关的按钮节点
        val unfollowTargets = mutableListOf<AccessibilityNodeInfo>()
        findUnfollowNodes(rootNode, unfollowTargets)

        if (unfollowTargets.isNotEmpty()) {
            // 根据模式决定排序顺序：
            // 模式一 (自下而上)：从屏幕最底部向顶部点击取关 (最旧账户优先)
            // 模式二 (自上而下)：从屏幕顶部向底部点击取关
            if (config.mode == ExecutionMode.BOTTOM_FIRST) {
                unfollowTargets.sortByDescending { getNodeBounds(it).bottom }
            } else {
                unfollowTargets.sortBy { getNodeBounds(it).top }
            }

            val target = unfollowTargets[0]
            val clicked = performNodeClick(target)

            if (clicked) {
                config.unfollowedCount++
                if (config.maxUnfollowLimit > 0 && config.unfollowedCount >= config.maxUnfollowLimit) {
                    stopTask()
                    updateState(TaskState.STOPPED, "已完成目标取关上限 (${config.maxUnfollowLimit}人)！")
                    return
                }
                val progressStr = if (config.maxUnfollowLimit > 0) {
                    "已自动取关: ${config.unfollowedCount} / ${config.maxUnfollowLimit} 人"
                } else {
                    "已自动取关: ${config.unfollowedCount} 人"
                }
                updateState(TaskState.UNFOLLOWING, progressStr)
            } else {
                updateState(TaskState.UNFOLLOWING, "正在尝试点击...")
            }
        } else {
            // 当前屏幕无匹配取关按钮
            if (config.mode == ExecutionMode.BOTTOM_FIRST) {
                // 模式一：向下回滑屏幕（向上寻找更早关注的用户）
                performSwipeDownSmall()
                val progressStr = if (config.maxUnfollowLimit > 0) {
                    "已取关: ${config.unfollowedCount}/${config.maxUnfollowLimit}人 | 向上翻寻早期关注..."
                } else {
                    "已取关: ${config.unfollowedCount}人 | 向上翻寻早期关注..."
                }
                updateState(TaskState.UNFOLLOWING, progressStr)
            } else {
                // 模式二：向上滑动屏幕（载入更多下方用户）
                performSwipeUpSmall()
                val progressStr = if (config.maxUnfollowLimit > 0) {
                    "已取关: ${config.unfollowedCount}/${config.maxUnfollowLimit}人 | 载入下页..."
                } else {
                    "已取关: ${config.unfollowedCount}人 | 载入下页..."
                }
                updateState(TaskState.UNFOLLOWING, progressStr)
            }
        }
    }

    /**
     * 递归遍历 UI 树寻找符合目标筛选条件的取关按钮节点
     */
    private fun findUnfollowNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()

        if (isUnfollowText(text, config.targetType) || isUnfollowText(contentDesc, config.targetType)) {
            result.add(node)
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findUnfollowNodes(child, result)
        }
    }

    private fun isUnfollowText(str: String?, targetType: TargetType): Boolean {
        if (str == null) return false
        val s = str.trim()
        val isFollowing = s == "已关注" || s == "Following"
        val isFriends = s == "好友" || s == "Friends" || s == "互相关注"

        return when (targetType) {
            TargetType.ALL -> isFollowing || isFriends
            TargetType.FOLLOWING_ONLY -> isFollowing
            TargetType.FRIENDS_ONLY -> isFriends
        }
    }

    private fun getNodeBounds(node: AccessibilityNodeInfo): android.graphics.Rect {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    /**
     * 执行节点点击（优先触发 Node Click，后备方案触发 View Bounds 坐标中心模拟手势）
     */
    private fun performNodeClick(node: AccessibilityNodeInfo): Boolean {
        var clickableNode: AccessibilityNodeInfo? = node
        while (clickableNode != null) {
            if (clickableNode.isClickable) {
                return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            clickableNode = clickableNode.parent
        }

        // 后备坐标手势点击
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val clickX = rect.centerX().toFloat()
            val clickY = rect.centerY().toFloat()
            return dispatchClickGesture(clickX, clickY)
        }
        return false
    }

    /**
     * 模拟手势向上滑动 (标准翻页)
     */
    private fun performSwipeUp() {
        val displayMetrics = resources.displayMetrics
        val startX = (displayMetrics.widthPixels / 2).toFloat()
        val startY = (displayMetrics.heightPixels * 0.8f)
        val endY = (displayMetrics.heightPixels * 0.2f)

        dispatchSwipeGesture(startX, startY, startX, endY, 350L)
    }

    /**
     * 模拟手势向上微调滑动 (小幅度翻页)
     */
    private fun performSwipeUpSmall() {
        val displayMetrics = resources.displayMetrics
        val startX = (displayMetrics.widthPixels / 2).toFloat()
        val startY = (displayMetrics.heightPixels * 0.7f)
        val endY = (displayMetrics.heightPixels * 0.4f)

        dispatchSwipeGesture(startX, startY, startX, endY, 250L)
    }

    /**
     * 模拟手势向下微调滑动 (自上而下滑动屏幕，向上回寻早期关注)
     */
    private fun performSwipeDownSmall() {
        val displayMetrics = resources.displayMetrics
        val startX = (displayMetrics.widthPixels / 2).toFloat()
        val startY = (displayMetrics.heightPixels * 0.3f)
        val endY = (displayMetrics.heightPixels * 0.6f)

        dispatchSwipeGesture(startX, startY, startX, endY, 250L)
    }

    private fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    private fun dispatchClickGesture(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50L)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        return dispatchGesture(builder.build(), null, null)
    }
}
