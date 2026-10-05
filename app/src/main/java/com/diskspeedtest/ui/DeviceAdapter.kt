package com.diskspeedtest.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diskspeedtest.R
import com.diskspeedtest.databinding.ItemStorageDeviceBinding
import com.diskspeedtest.model.StorageInfo

/**
 * 存储设备列表适配器
 */
class DeviceAdapter(
    private val onDeviceClick: (StorageInfo) -> Unit
) : ListAdapter<StorageInfo, DeviceAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var selectedPosition: Int = -1

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StorageInfo>() {
            override fun areItemsTheSame(oldItem: StorageInfo, newItem: StorageInfo): Boolean {
                return oldItem.path == newItem.path
            }

            override fun areContentsTheSame(oldItem: StorageInfo, newItem: StorageInfo): Boolean {
                return oldItem == newItem
            }
        }
    }

    inner class ViewHolder(val binding: ItemStorageDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStorageDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        with(holder.binding) {
            tvDeviceName.text = device.name
            tvDeviceType.text = device.typeDescription
            tvDeviceSpace.text = "${device.readableUsed} / ${device.readableTotal}"
            pbDeviceUsage.progress = device.usedPercent

            // 根据设备类型选择图标
            val iconRes = when {
                device.isRemovable && !device.isEmulated -> R.drawable.ic_sd
                device.isEmulated -> R.drawable.ic_internal
                else -> R.drawable.ic_usb
            }
            ivDeviceIcon.setImageResource(iconRes)

            // 选中状态
            val isSelected = position == selectedPosition
            ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            root.strokeColor = if (isSelected) {
                root.context.getColor(R.color.primary)
            } else {
                root.context.getColor(R.color.divider)
            }

            root.setOnClickListener {
                val oldPos = selectedPosition
                selectedPosition = holder.bindingAdapterPosition
                if (oldPos != -1) notifyItemChanged(oldPos)
                notifyItemChanged(selectedPosition)
                onDeviceClick(device)
            }
        }
    }

    /** 获取当前选中的设备 */
    fun getSelectedDevice(): StorageInfo? {
        return if (selectedPosition in 0 until itemCount) getItem(selectedPosition) else null
    }

    /** 清除选中 */
    fun clearSelection() {
        if (selectedPosition != -1) {
            val oldPos = selectedPosition
            selectedPosition = -1
            notifyItemChanged(oldPos)
        }
    }
}
