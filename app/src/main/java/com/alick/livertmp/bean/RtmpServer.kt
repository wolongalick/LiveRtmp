package com.alick.livertmp.bean

import java.io.Serializable

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/22 19:32
 */
class RtmpServer(var alias:String, var host: String, var isSelected: Boolean = false, val isEnableEdit: Boolean = false) : Serializable{
    override fun toString(): String {
        return "RtmpServer(alias='$alias', host='$host', selected=$isSelected, isEnableEdit=$isEnableEdit)"
    }
}
