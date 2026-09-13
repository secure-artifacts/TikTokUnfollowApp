package com.example.tiktokunfollow.model

enum class TargetType {
    ALL,             // 全部取关 (已关注 + 好友)
    FOLLOWING_ONLY,  // 仅取关“已关注” (不含好友)
    FRIENDS_ONLY     // 仅取关“好友/互相关注”
}

enum class ExecutionMode {
    SKIP_N_FIRST,  // 模式一：跳过最新关注的N个/滑动N次后再取关
    BOTTOM_FIRST   // 模式二：翻到最底部(早期关注)后再自下而上取关
}

enum class TaskState {
    IDLE,
    SCROLLING_TO_BOTTOM,
    SKIPPING_OFFSET,
    UNFOLLOWING,
    PAUSED,
    STOPPED
}

data class UnfollowTaskConfig(
    var targetType: TargetType = TargetType.ALL,
    var mode: ExecutionMode = ExecutionMode.SKIP_N_FIRST,
    var skipCount: Int = 10,       // N 对应需跳过/翻页的数字
    var intervalMs: Long = 2000L,  // 操作间隔毫秒
    var unfollowedCount: Int = 0,  // 已取关计数
    var maxUnfollowLimit: Int = 0  // 取关上限 (0表示不限制)
)
