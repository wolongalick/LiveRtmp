package com.alick.livertmp

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import com.alick.livertmp.bean.RtmpServer
import com.alick.livertmp.databinding.ItemServerBinding
import com.alick.livertmp.databinding.ItemServerEditBinding
import com.alick.livertmp.utils.SpConstant
import com.alick.utilslibrary.StorageUtils

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/22 19:31
 */
class RtmpServerAdapter(private val rtmpServerList: MutableList<RtmpServer>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val type_normal = 1
    private val type_edit = 2
    private val onTextChanged = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }

        override fun afterTextChanged(s: Editable) {
            rtmpServerList.find { it.isEnableEdit }?.host =s.toString()
        }
    }

    class RtmpServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int {
        val rtmpServer = rtmpServerList[position]
        return if (rtmpServer.isEnableEdit) {
            type_edit
        } else {
            type_normal
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == type_edit) {
            val itemView = ItemServerEditBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            RtmpServerViewHolder(itemView.root)
        } else {
            val itemView = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            RtmpServerViewHolder(itemView.root)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val rtmpServer = rtmpServerList[position]
        if (rtmpServer.isEnableEdit) {
            val bind = ItemServerEditBinding.bind(holder.itemView)
            bind.tvHost.setText(rtmpServer.host)
            bind.tvAlias.text = rtmpServer.alias
            bind.ivSelector.isSelected = rtmpServer.isSelected
            bind.serverRootView.setOnClickListener {
                if (rtmpServer.isSelected) {
                    return@setOnClickListener
                }
                rtmpServerList.forEachIndexed { index, rtmpServer ->
                    if (position == index) {
                        rtmpServer.isSelected = !rtmpServer.isSelected
                    } else {
                        rtmpServer.isSelected = false
                    }
                }
                notifyDataSetChanged()
                StorageUtils.setInt(SpConstant.SELECTED_RTMP_URL_INDEX, position)
            }
            bind.tvHost.removeTextChangedListener(onTextChanged)
            bind.tvHost.addTextChangedListener(onTextChanged)


        } else {
            val bind = ItemServerBinding.bind(holder.itemView)
            bind.tvHost.text = rtmpServer.host
            bind.tvAlias.text = rtmpServer.alias
            bind.ivSelector.isSelected = rtmpServer.isSelected
            bind.serverRootView.setOnClickListener {
                if (rtmpServer.isSelected) {
                    return@setOnClickListener
                }

                rtmpServerList.forEachIndexed { index, rtmpServer ->
                    if (position == index) {
                        rtmpServer.isSelected = !rtmpServer.isSelected
                    } else {
                        rtmpServer.isSelected = false
                    }
                }
                notifyDataSetChanged()
                StorageUtils.setInt(SpConstant.SELECTED_RTMP_URL_INDEX, position)
            }
        }
    }

    override fun getItemCount(): Int {
        return rtmpServerList.size
    }

    fun getSelectedRtmpServer(): RtmpServer? {
        return rtmpServerList.find { it.isSelected }
    }

}