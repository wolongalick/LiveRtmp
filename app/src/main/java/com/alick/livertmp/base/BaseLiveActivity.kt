package com.alick.livertmp.base

import androidx.viewbinding.ViewBinding
import com.alick.commonlibrary.BaseActivity
import com.alick.livertmp.bean.RtmpServer
import com.alick.livertmp.constant.LiveConstant

/**
 * @createTime 2025/7/12 11:23
 * @author 崔兴旺
 * @description
 */
abstract class BaseLiveActivity<Binding : ViewBinding> : BaseActivity<Binding>() {

    protected val liveRoomUrl: RtmpServer by lazy {
        intent.getSerializableExtra(LiveConstant.INTENT_KEY_LIVE_ROOM_URL) as RtmpServer
    }
    protected var rtmpConnectState = false  //是否连接rtmp成功


}