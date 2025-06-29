package com.codenzi.ceparsivi

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.codenzi.ceparsivi.databinding.ItemFileBinding
import com.codenzi.ceparsivi.databinding.ItemFileGridBinding
import com.codenzi.ceparsivi.databinding.ItemHeaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val VIEW_TYPE_FILE_LIST = 1
private const val VIEW_TYPE_FILE_GRID = 2

enum class ViewMode {
    LIST, GRID
}

object ThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    val memoryCache = LruCache<String, Bitmap>(cacheSize)
}

class ArchivedFileAdapter(
    private val onItemClick: (ArchivedFile) -> Unit,
    private val onItemLongClick: (ArchivedFile) -> Boolean
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(ListItemDiffCallback()) {

    companion object {
        const val VIEW_TYPE_HEADER = 0
    }

    var viewMode: ViewMode = ViewMode.LIST
    private val selectedItems = mutableSetOf<String>()
    var isSelectionMode = false

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is GridViewHolder) {
            holder.cancelJob()
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (position < 0 || position >= itemCount) {
            return VIEW_TYPE_FILE_LIST
        }
        return when (getItem(position)) {
            is ListItem.HeaderItem -> VIEW_TYPE_HEADER
            is ListItem.FileItem -> if (viewMode == ViewMode.LIST) VIEW_TYPE_FILE_LIST else VIEW_TYPE_FILE_GRID
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(ItemHeaderBinding.inflate(inflater, parent, false))
            VIEW_TYPE_FILE_LIST -> ListViewHolder(ItemFileBinding.inflate(inflater, parent, false))
            VIEW_TYPE_FILE_GRID -> GridViewHolder(ItemFileGridBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val currentItem = getItem(position)
        val isSelected = if (currentItem is ListItem.FileItem) {
            selectedItems.contains(currentItem.archivedFile.filePath)
        } else {
            false
        }

        when (holder) {
            is HeaderViewHolder -> holder.bind(currentItem as ListItem.HeaderItem)
            is ListViewHolder -> holder.bind((currentItem as ListItem.FileItem).archivedFile, onItemClick, onItemLongClick, isSelected)
            is GridViewHolder -> holder.bind((currentItem as ListItem.FileItem).archivedFile, onItemClick, onItemLongClick, isSelected)
        }
    }

    class ListViewHolder(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: ArchivedFile, onClick: (ArchivedFile) -> Unit, onLongClick: (ArchivedFile) -> Boolean, isSelected: Boolean) {
            binding.textViewFileName.text = file.fileName
            binding.textViewFileDate.text = file.dateAdded
            binding.imageViewFileType.setImageResource(getFileIcon(file.categoryResId, file.fileName))
            binding.root.setBackgroundColor(if (isSelected) ContextCompat.getColor(itemView.context, R.color.purple_200) else Color.TRANSPARENT)
            itemView.setOnClickListener { onClick(file) }
            itemView.setOnLongClickListener { onLongClick(file) }
        }
    }

    class GridViewHolder(private val binding: ItemFileGridBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbnailJob: Job? = null

        fun cancelJob() {
            thumbnailJob?.cancel()
            thumbnailJob = null
        }

        fun bind(file: ArchivedFile, onClick: (ArchivedFile) -> Unit, onLongClick: (ArchivedFile) -> Boolean, isSelected: Boolean) {
            binding.textViewFileNameGrid.text = file.fileName
            val extension = file.fileName.substringAfterLast('.', "").lowercase()

            cancelJob()

            binding.imageViewFileTypeGrid.imageTintList = null
            binding.imageViewFileTypeGrid.scaleType = ImageView.ScaleType.CENTER_CROP

            when (file.categoryResId) {
                R.string.category_images, R.string.category_videos -> {
                    Glide.with(itemView.context)
                        .load(File(file.filePath))
                        .placeholder(R.drawable.ic_file_generic)
                        .error(getFileIcon(file.categoryResId, file.fileName))
                        .into(binding.imageViewFileTypeGrid)
                }
                else -> {
                    if (extension == "pdf") {
                        val cachedBitmap = ThumbnailCache.memoryCache.get(file.filePath)
                        if (cachedBitmap != null) {
                            binding.imageViewFileTypeGrid.setImageBitmap(cachedBitmap)
                        } else {
                            thumbnailJob = generatePdfPreview(file)
                        }
                    } else {
                        setGenericIcon(file)
                    }
                }
            }

            val strokeColorInt = ContextCompat.getColor(itemView.context, R.color.purple_500)
            binding.root.strokeWidth = if (isSelected) 8 else 0
            binding.root.strokeColor = if (isSelected) strokeColorInt else Color.TRANSPARENT

            itemView.setOnClickListener { onClick(file) }
            itemView.setOnLongClickListener { onLongClick(file) }
        }

        private fun setGenericIcon(file: ArchivedFile) {
            val typedValue = TypedValue()
            itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            binding.imageViewFileTypeGrid.imageTintList = ColorStateList.valueOf(typedValue.data)
            binding.imageViewFileTypeGrid.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.imageViewFileTypeGrid.setImageResource(getFileIcon(file.categoryResId, file.fileName))
        }

        private fun generatePdfPreview(file: ArchivedFile): Job? {
            binding.imageViewFileTypeGrid.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.imageViewFileTypeGrid.setImageResource(R.drawable.ic_file_pdf)

            return (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                try {
                    val fileDescriptor = ParcelFileDescriptor.open(File(file.filePath), ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(fileDescriptor)
                    val page = renderer.openPage(0)

                    val displayMetrics = itemView.context.resources.displayMetrics
                    val targetWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100f, displayMetrics).toInt()
                    val scale = targetWidth.toFloat() / page.width
                    val targetHeight = (page.height * scale).toInt()

                    if (targetWidth <= 0 || targetHeight <= 0) {
                        throw IllegalStateException("Invalid thumbnail dimensions")
                    }

                    val bitmap = createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)

                    val renderRect = Rect(0, 0, targetWidth, targetHeight)
                    page.render(bitmap, renderRect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    ThumbnailCache.memoryCache.put(file.filePath, bitmap)

                    page.close()
                    renderer.close()
                    fileDescriptor.close()

                    withContext(Dispatchers.Main) {
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            binding.imageViewFileTypeGrid.scaleType = ImageView.ScaleType.CENTER_CROP
                            binding.imageViewFileTypeGrid.imageTintList = null
                            binding.imageViewFileTypeGrid.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PdfPreview", "PDF önizlemesi oluşturulamadı: ${file.fileName}", e)
                    withContext(Dispatchers.Main) {
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            setGenericIcon(file)
                        }
                    }
                }
            }
        }
    }

    class HeaderViewHolder(private val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: ListItem.HeaderItem) {
            binding.textViewHeader.text = header.title
        }
    }

    fun toggleSelection(filePath: String) {
        if (selectedItems.contains(filePath)) {
            selectedItems.remove(filePath)
        } else {
            selectedItems.add(filePath)
        }
        val index = currentList.indexOfFirst { it is ListItem.FileItem && it.archivedFile.filePath == filePath }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    fun getSelectedFileCount(): Int = selectedItems.size

    fun getSelectedFiles(allItems: List<ListItem>): List<ArchivedFile> {
        return allItems.filterIsInstance<ListItem.FileItem>()
            .map { it.archivedFile }
            .filter { selectedItems.contains(it.filePath) }
    }

    fun clearSelections() {
        val positionsToUpdate = mutableListOf<Int>()
        selectedItems.forEach { filePath ->
            val index = currentList.indexOfFirst { it is ListItem.FileItem && it.archivedFile.filePath == filePath }
            if (index != -1) {
                positionsToUpdate.add(index)
            }
        }
        selectedItems.clear()
        isSelectionMode = false
        positionsToUpdate.forEach { notifyItemChanged(it) }
    }
}

private fun getFileIcon(categoryResId: Int, fileName: String): Int {
    if (categoryResId == R.string.category_office) {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> R.drawable.ic_file_pdf
            "doc", "docx" -> R.drawable.ic_file_doc
            "ppt", "pptx" -> R.drawable.ic_file_doc
            else -> R.drawable.ic_file_generic
        }
    }
    return when (categoryResId) {
        R.string.category_images -> R.drawable.ic_file_image
        R.string.category_videos -> R.drawable.ic_file_video
        R.string.category_audio -> R.drawable.ic_file_audio
        R.string.category_archives -> R.drawable.ic_file_archive
        else -> R.drawable.ic_file_generic
    }
}

class ListItemDiffCallback : DiffUtil.ItemCallback<ListItem>() {
    override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
        return when {
            oldItem is ListItem.FileItem && newItem is ListItem.FileItem ->
                oldItem.archivedFile.filePath == newItem.archivedFile.filePath
            oldItem is ListItem.HeaderItem && newItem is ListItem.HeaderItem ->
                oldItem.title == newItem.title
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
        return oldItem == newItem
    }
}
