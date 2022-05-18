package com.alick.livertmp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alick.livertmp.bean.RtmpServer
import com.alick.livertmp.databinding.ItemServerBinding
import com.alick.livertmp.databinding.ItemServerEditBinding

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/22 19:31
 */
class RtmpServerAdapter(private val context:Context,private val rtmpServerList: MutableList<RtmpServer>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val type_normal = 1
    private val type_edit = 2

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
            val itemView = ItemServerEditBinding.inflate(LayoutInflater.from(context))
            RtmpServerViewHolder(itemView.root)
        } else {
            val itemView = ItemServerBinding.inflate(LayoutInflater.from(context))
            RtmpServerViewHolder(itemView.root)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val rtmpServer = rtmpServerList[position]
        if (rtmpServer.isEnableEdit) {
            val bind = ItemServerEditBinding.bind(holder.itemView)
            bind.tvHost.setText(rtmpServer.host)
            bind.tvAlias.text = rtmpServer.alias
            bind.ivSelector.isSelected = rtmpServer.selected
            bind.ivSelector.setOnClickListener {
                rtmpServerList.forEach {
                    bind.ivSelector.isSelected = !it.selected
                }
            }
        } else {
            val bind = ItemServerBinding.bind(holder.itemView)
            bind.tvHost.text = rtmpServer.host
            bind.tvAlias.text = rtmpServer.alias
            bind.ivSelector.isSelected = rtmpServer.selected
            bind.ivSelector.setOnClickListener {
                rtmpServerList.forEach {
                    bind.ivSelector.isSelected = !it.selected
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return rtmpServerList.size
    }

    fun getSelectedRtmpServer(): RtmpServer? {
        return rtmpServerList.find { it.selected }
    }

}