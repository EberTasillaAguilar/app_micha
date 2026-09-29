package com.arduino.bluetooth.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arduino.bluetooth.databinding.ItemFileBinding
import com.arduino.bluetooth.models.SavedFileInfo

class FilesAdapter(
    private var files: MutableList<SavedFileInfo>,
    private val onFileClick: (SavedFileInfo) -> Unit,
    private val onShareClick: (SavedFileInfo) -> Unit,
    private val onDeleteClick: (SavedFileInfo, Int) -> Unit
) : RecyclerView.Adapter<FilesAdapter.FileViewHolder>() {

    inner class FileViewHolder(val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = files[position]
        holder.binding.tvFileName.text = item.name
        holder.binding.tvFileInfo.text = "${item.sizeText} • ${item.dateText}"

        holder.itemView.setOnClickListener {
            onFileClick(item)
        }

        holder.binding.btnShareFile.setOnClickListener {
            onShareClick(item)
        }

        holder.binding.btnDeleteFile.setOnClickListener {
            onDeleteClick(item, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int = files.size

    fun removeAt(position: Int) {
        if (position in 0 until files.size) {
            files.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun updateList(newList: List<SavedFileInfo>) {
        files = newList.toMutableList()
        notifyDataSetChanged()
    }
}
