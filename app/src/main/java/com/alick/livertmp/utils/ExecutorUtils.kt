package com.alick.livertmp.utils

import java.util.concurrent.*

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/11 21:01
 */
object ExecutorUtils {
    private val executorService: ExecutorService = Executors.newFixedThreadPool(2);
    fun getExecutor2(): ExecutorService {
        return executorService
    }
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