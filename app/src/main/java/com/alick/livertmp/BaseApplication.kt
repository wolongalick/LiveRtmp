package com.alick.livertmp

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import com.alick.utilslibrary.AppHolder

/**
 * @author 崔兴旺
 * @description
 * @date 2022/3/13 18:58
 */
class BaseApplication : Application(), CameraXConfig.Provider {
    override fun onCreate() {
        super.onCreate()
        AppHolder.init(this)
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())


//            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)//限制使用后置摄像头
            .build()
    }
}