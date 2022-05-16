package com.alick.livertmp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.alick.commonlibrary.BaseActivity
import com.alick.livertmp.databinding.ActivityMainBinding
import com.alick.livertmp.live.CameraLiveActivity
import com.alick.utilslibrary.AppHolder
import com.alick.utilslibrary.T

class MainActivity : BaseActivity<ActivityMainBinding>() {
    private val PERMISS_CODE_CAMERA = 1000
    val toast = Toast(AppHolder.getApp())
    override fun initListener() {
        viewBinding.btnCameraLive.setOnClickListener {
            requestPermissions()
        }

        viewBinding.btnToast.setOnClickListener {
            viewBinding.btnToast.postDelayed({

                val view = View.inflate(AppHolder.getApp(),R.layout.toast, null)
                toast.view = view
                toast.setGravity(Gravity.START or Gravity.TOP, 0, 0)
                toast.duration = Toast.LENGTH_LONG
                toast.show()
            }, 0)
        }

        viewBinding.btnCloseToast.setOnClickListener {
            toast.cancel()
        }
    }

    override fun initData() {

    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED) {
            startActivity(Intent(this, CameraLiveActivity::class.java))
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
                    startActivity(Intent(this, CameraLiveActivity::class.java))
                } else {
                    T.show("请授予app摄像头权限")
                }
            }
        }

    }

}