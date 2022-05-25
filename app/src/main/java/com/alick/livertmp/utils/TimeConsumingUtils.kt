package com.alick.livertmp.utils


/**
 * @author 崔兴旺
 * @description 用来统计统一代码块被执行固定次之后的耗时
 * @date 2022/5/25 1:40
 */
class TimeConsumingUtils(private val numberOfExecutions: Int,private val onResult: (duration: Long) -> Unit) {

    private var firstExecuteTs = 0L    //第一次执行时的时间戳
    private var executeCount = 0       //已执行次数

    /**
     * 运行并统计时长
     * @param block     需要被统计的代码块
     * @param onResult  统计结果(每次统计完时才会回调onResult方法,duration单位:毫秒)
     */
    fun runAndStatistics(block: (() -> Unit)) {
        if (executeCount == 0) {
            firstExecuteTs = System.currentTimeMillis()
        }
        block()
        executeCount++
        if (executeCount == numberOfExecutions) {
            val currentTimeMillis = System.currentTimeMillis()
            onResult(currentTimeMillis - firstExecuteTs)
            firstExecuteTs = currentTimeMillis
            executeCount = 0
        }
    }
}