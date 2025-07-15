package com.alick.livertmp

import android.Manifest
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alick.commonlibrary.BaseActivity
import com.alick.livertmp.bean.RtmpServer
import com.alick.livertmp.constant.LiveConstant
import com.alick.livertmp.databinding.ActivityMainBinding
import com.alick.livertmp.live.CameraLiveActivity
import com.alick.livertmp.utils.SpConstant
import com.alick.utilslibrary.StorageUtils
import com.alick.utilslibrary.T

class MainActivity : BaseActivity<ActivityMainBinding>() {
    private val PERMISS_CODE = 1000

    private val rtmpServerAdapter: RtmpServerAdapter by lazy {
        RtmpServerAdapter(
            mutableListOf(
                RtmpServer("B站", "rtmp://live-push.bilivideo.com/live-bvc/?streamname=live_49872005_3293420&key=5c0c6bdce470a12211ffe3f15ab50dae&schedule=rtmp&pflag=1"),
                RtmpServer("公网", "rtmp://39.105.182.221/myapp/camera"),
                RtmpServer("局域网", "rtmp://10.111.0.103/live"),
                RtmpServer("自定义", "rtmp://10.111.0.103/live/1", isEnableEdit = true),
            ).apply {
                this[StorageUtils.getInt(SpConstant.SELECTED_RTMP_URL_INDEX)].isSelected = true
            }
        )
    }

    override fun initListener() {
        binding.btnCameraLive.setOnClickListener {
            requestPermissions()
        }
        binding.rvServer.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.rvServer.adapter = rtmpServerAdapter

        binding.btnScreenShotLive.setOnClickListener {
            val rtmpServer = rtmpServerAdapter.getSelectedRtmpServer()
            if (rtmpServer == null || rtmpServer.host.isEmpty()) {
                T.show("请填写rtmp服务器地址")
                return@setOnClickListener
            }

            if (rtmpServer.isEnableEdit) {
                StorageUtils.setString(SpConstant.CUSTOM_RTMP_URL, rtmpServer.host)
            }
            startActivity(
                Intent(this@MainActivity, ScreenShotActivity::class.java)
                    .putExtra(LiveConstant.INTENT_KEY_LIVE_ROOM_URL, rtmpServer)
            )
        }

    }

    override fun initData() {

    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED
        ) {
            gotoCameraLiveActivity()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), PERMISS_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISS_CODE -> {
                grantResults.forEachIndexed { index, i ->
                    if (i == PermissionChecker.PERMISSION_DENIED) {
                        when (permissions[index]) {
                            Manifest.permission.CAMERA -> {
                                T.show("请授予打开摄像头权限")
                            }

                            Manifest.permission.RECORD_AUDIO -> {
                                T.show("请授予录音权限")
                            }
                        }
                        return
                    }
                }
                gotoCameraLiveActivity()
            }
        }
    }

    private fun gotoCameraLiveActivity() {
        val rtmpServer = rtmpServerAdapter.getSelectedRtmpServer()
        if (rtmpServer == null || rtmpServer.host.isEmpty()) {
            T.show("请填写rtmp服务器地址")
            return
        }

        if (rtmpServer.isEnableEdit) {
            StorageUtils.setString(SpConstant.CUSTOM_RTMP_URL, rtmpServer.host)
        }
        startActivity(Intent(this, CameraLiveActivity::class.java)
            .putExtra(LiveConstant.INTENT_KEY_LIVE_ROOM_URL, rtmpServer)
        )
    }

}