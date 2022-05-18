package com.alick.livertmp.bean

import java.io.Serializable

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/22 19:32
 */
class RtmpServer(val alias:String,val host: String, val selected: Boolean = false, val isEnableEdit: Boolean = false) : Serializable
