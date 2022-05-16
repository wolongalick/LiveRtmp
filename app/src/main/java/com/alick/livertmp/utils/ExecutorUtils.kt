package com.alick.livertmp.utils

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/11 21:01
 */
object ExecutorUtils {
    fun getExecutor(): ThreadPoolExecutor {
        return ThreadPoolExecutor(
            5,
            10,
            100,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(5)
        )
    }
}