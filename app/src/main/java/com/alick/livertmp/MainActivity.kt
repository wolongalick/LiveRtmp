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
    private val PERMISS_CODE_CAMERA = 1000

    private val rtmpServerAdapter: RtmpServerAdapter by lazy {
        RtmpServerAdapter(
            mutableListOf(
                RtmpServer("B站", "rtmp://live-push.bilivideo.com/live-bvc/?streamname=live_49872005_3293420&key=5c0c6bdce470a12211ffe3f15ab50dae&schedule=rtmp&pflag=1"),
                RtmpServer("公网", "rtmp://39.105.182.221/myapp/camera"),
                RtmpServer("局域网", "rtmp://192.168.0.103/myapp/camera"),
                RtmpServer("自定义", "", isEnableEdit = true),
            ).apply {
                this[StorageUtils.getInt(SpConstant.SELECTED_RTMP_URL_INDEX)].isSelected = true
            }
        )
    }

    override fun initListener() {
        viewBinding.btnCameraLive.setOnClickListener {
            requestPermissions()
        }
        viewBinding.rvServer.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        viewBinding.rvServer.adapter = rtmpServerAdapter


    }

    override fun initData() {

    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED) {
            gotoCameraLiveActivity()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISS_CODE_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISS_CODE_CAMERA -> {
                if (grantResults.all {
                        it == PermissionChecker.PERMISSION_GRANTED
                    }) {
                    gotoCameraLiveActivity()
                } else {
                    T.show("请授予app摄像头权限")
                }
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
        startActivity(Intent(this, CameraLiveActivity::class.java).putExtra(LiveConstant.INTENT_KEY_LIVE_ROOM_URL, rtmpServer))
    }

}