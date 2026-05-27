package nocom.rian.clipboardmanager.ui

import nocom.rian.clipboardmanager.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import nocom.rian.clipboardmanager.databinding.ItemClipboardBinding
import nocom.rian.clipboardmanager.db.ClipboardItem
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

data class ClipDisplayConfig(
    val showAppName: Boolean = true,
    val showPackageName: Boolean = true,
    val showWindowLabel: Boolean = true,
    val showActivityName: Boolean = true,
    val showLabel: Boolean = true,
    val showTechnicalDetails: Boolean = true,
    val showPrevAppName: Boolean = true,
    val showPrevPackageName: Boolean = true,
    val showPrevWindowLabel: Boolean = true,
    val showPrevActivityName: Boolean = true
)

class ClipboardAdapter(
    private val onPinClick: (ClipboardItem) -> Unit,
    private val onExpandClick: (Int) -> Unit,
    private val onLongClick: (ClipboardItem) -> Unit,
    private val onDeleteClick: (ClipboardItem) -> Unit,
    private val onCopyClick: (ClipboardItem) -> Unit,
    private val onFavoriteClick: (ClipboardItem) -> Unit,
    private val onSensitiveClick: (ClipboardItem) -> Unit,
    private val onLabelEditClick: (ClipboardItem) -> Unit,
    private val onRevealClick: (Int) -> Unit,
    private val onEditClick: (ClipboardItem) -> Unit
) : ListAdapter<ClipboardItem, ClipboardAdapter.ClipboardViewHolder>(DiffCallback) {

    private var expandedIds: Set<Int> = emptySet()
    private val pendingAnimationIds: MutableSet<Int> = mutableSetOf()
    private var localStateOverrides: Map<Int, ClipboardItem> = emptyMap()
    private var revealedIds: Set<Int> = emptySet()
    private var selectedIds: Set<Int> = emptySet()
    private var displayConfig: ClipDisplayConfig = ClipDisplayConfig()

    // =====================================================================
    // 1. Public State Configuration APIs
    // =====================================================================

    fun setExpandedIds(ids: Set<Int>) {
        val oldIds = expandedIds
        expandedIds = ids
        val changed = (oldIds - ids) + (ids - oldIds)
        pendingAnimationIds.addAll(changed)
        currentList.forEachIndexed { index, item ->
            if (changed.contains(item.id)) notifyItemChanged(index)
        }
    }
    
    fun setRevealedIds(ids: Set<Int>) {
        val oldIds = revealedIds
        revealedIds = ids
        val changed = (oldIds - ids) + (ids - oldIds)
        currentList.forEachIndexed { index, item ->
            if (changed.contains(item.id)) notifyItemChanged(index)
        }
    }
    
    fun setSelectedIds(ids: Set<Int>) {
        val oldIds = selectedIds
        selectedIds = ids
        val changed = (oldIds - ids) + (ids - oldIds)
        currentList.forEachIndexed { index, item ->
            if (changed.contains(item.id)) notifyItemChanged(index)
        }
    }
    
    fun setDisplayConfig(config: ClipDisplayConfig) {
        if (config == displayConfig) return
        displayConfig = config
        notifyDataSetChanged()
    }

    // =====================================================================
    // 2. RecyclerView Adapter Lifecycle Methods
    // =====================================================================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipboardViewHolder {
        val binding = ItemClipboardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ClipboardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ClipboardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    override fun onBindViewHolder(holder: ClipboardViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position))
        } else {
            holder.bindState(getItem(position))
        }
    }

    // =====================================================================
    // 3. Private Animation Helpers
    // =====================================================================

    private fun animateExpansion(view: View, expand: Boolean) {
        (view.tag as? android.animation.ValueAnimator)?.cancel()
    
        if (expand) {
            view.visibility = View.VISIBLE
            view.measure(
                View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = view.measuredHeight
            view.layoutParams.height = 0
            view.requestLayout()
    
            android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 280
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    view.layoutParams.height = it.animatedValue as Int
                    view.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.tag = null
                    }
                })
                view.tag = this
                start()
            }
        } else {
            val initialHeight = view.measuredHeight
            android.animation.ValueAnimator.ofInt(initialHeight, 0).apply {
                duration = 220
                interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener {
                    view.layoutParams.height = it.animatedValue as Int
                    view.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        view.visibility = View.GONE
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.tag = null
                    }
                })
                view.tag = this
                start()
            }
        }
    }
    
    private fun animateArrow(arrow: View, expand: Boolean) {
        arrow.animate()
            .rotation(if (expand) 180f else 0f)
            .setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    // =====================================================================
    // 4. Inner ViewHolder Implementation
    // =====================================================================

    inner class ClipboardViewHolder(private val binding: ItemClipboardBinding) :
        RecyclerView.ViewHolder(binding.root) {
    
        private var currentItemId: Int = -1
        private var isCurrentlyExpanded: Boolean? = null
        
        // --- High-Level UI Rendering Tasks ---

        fun bind(item: ClipboardItem) {
            val effectiveItem = run {
                val ov = localStateOverrides[item.id]
                if (ov != null &&
                    ov.isPinned == item.isPinned &&
                    ov.isFavorite == item.isFavorite &&
                    ov.isSensitive == item.isSensitive) {
                    localStateOverrides = localStateOverrides - item.id
                    item
                } else {
                    ov ?: item
                }
            }
        
            val isImage           = item.mimeType?.startsWith("image/") == true
            val isExpanded        = expandedIds.contains(item.id)
            val isSelected        = selectedIds.contains(item.id)
            val shouldExpand      = isExpanded
            val isSensitiveHidden = effectiveItem.isSensitive && !revealedIds.contains(item.id)
            val shouldAnimate     = pendingAnimationIds.remove(item.id)
        
            val ctx                  = binding.root.context
            val colorOnSurface       = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
            val colorOnSurfaceVar    = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val colorOutlineVariant  = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
            val colorPinActive       = ContextCompat.getColor(ctx, R.color.color_pin_active)
            val colorSelectionBg     = ContextCompat.getColor(ctx, R.color.color_selection_background)
            val colorSelectionStroke = ContextCompat.getColor(ctx, R.color.color_selection_stroke)
            val colorSensitiveMask   = ContextCompat.getColor(ctx, R.color.color_sensitive_masked)
        
            binding.apply {
        
                // --- 1. Content Text / Graphics Display ---
                if (isSensitiveHidden) {
                    clipImage.visibility = View.GONE
                    clipText.visibility  = View.VISIBLE
                    clipText.text        = "🔒  Sensitive"
                    clipText.setTextColor(colorSensitiveMask)
                    clipText.maxLines    = 1
                    clipText.ellipsize   = null
                } else if (isImage) {
                    clipText.visibility  = View.GONE
                    clipImage.visibility = View.VISIBLE
                    loadImageSafely(clipImage, item.filePath ?: "")
                    if (shouldExpand) {
                        clipImage.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        clipImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    } else {
                        clipImage.layoutParams.height = 180.toPx(root.context)
                        clipImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                } else {
                    clipImage.visibility = View.GONE
                    clipText.visibility  = View.VISIBLE
                    clipText.text        = item.clip
                    clipText.setTextColor(colorOnSurface)
                    clipText.maxLines    = if (shouldExpand) Int.MAX_VALUE else 2
                    clipText.ellipsize   = if (shouldExpand) null else android.text.TextUtils.TruncateAt.END
                }
        
                // --- 2. Standard Metadata Row ---
                val typeLabel = if (isImage) "Image" else item.appName ?: "Unknown"
                metaText.text = "$typeLabel • ${item.time}"
                metaText.setTextColor(colorOnSurfaceVar)
        
                // --- 3. Optional Multi-Field Details Blocks ---
                if (displayConfig.showAppName) {
                    appNameText.visibility = View.VISIBLE
                    appNameText.text       = "App: ${item.appName ?: "Unknown"}"
                    appNameText.setTextColor(colorOnSurface)
                } else {
                    appNameText.visibility = View.GONE
                }
        
                if (displayConfig.showPackageName) {
                    fullPackageText.visibility = View.VISIBLE
                    fullPackageText.text       = "Package: ${item.packageName ?: "Unknown"}"
                    fullPackageText.setTextColor(colorOnSurfaceVar)
                } else {
                    fullPackageText.visibility = View.GONE
                }
        
                val extraText = buildString {
                    if (displayConfig.showTechnicalDetails) {
                        append("ID: ${item.id}\n")
                        append("Type: ${item.mimeType ?: "text/plain"}\n")
                        append("Date: ${item.date}\n")
                    }
                    if (displayConfig.showWindowLabel && item.windowLabel != null) {
                        append("Window: ${item.windowLabel}\n")
                    }
                    if (displayConfig.showActivityName && item.activityName != null) {
                        append("Activity: ${item.activityName}\n")
                    }
                    if (displayConfig.showLabel && item.label != null) {
                        append("Label: ${item.label}\n")
                    }
                    if (displayConfig.showPrevAppName && item.previousAppName != null) {
                        append("Prev App: ${item.previousAppName}\n")
                    }
                    if (displayConfig.showPrevPackageName && item.previousPackageName != null) {
                        append("Prev Package: ${item.previousPackageName}\n")
                    }
                    if (displayConfig.showPrevWindowLabel && item.previousWindowLabel != null) {
                        append("Prev Window: ${item.previousWindowLabel}\n")
                    }
                    if (displayConfig.showPrevActivityName && item.previousActivityName != null) {
                        append("Prev Activity: ${item.previousActivityName}\n")
                    }
                    if (displayConfig.showTechnicalDetails) {
                        if (isImage) append("Path: ${item.filePath}")
                        else append("Timestamp: ${item.timestamp}")
                    }
                }.trimEnd()
        
                if (extraText.isBlank()) {
                    extraDetailsText.visibility = View.GONE
                } else {
                    extraDetailsText.visibility = View.VISIBLE
                    extraDetailsText.text       = extraText
                    extraDetailsText.setTextColor(colorOnSurfaceVar)
                }
        
                // --- 4. Content Block Expansions ---
                val canExpand = shouldExpand && !isSensitiveHidden
                if (shouldAnimate) {
                    animateExpansion(detailContainer, canExpand)
                    animateArrow(expandArrow, canExpand)
                } else {
                    detailContainer.visibility = if (canExpand) View.VISIBLE else View.GONE
                    detailContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    expandArrow.rotation = if (canExpand) 180f else 0f
                }
        
                // --- 5. Selection Highlighting Structural Changes ---
                root.isChecked = isSelected
                root.setCardBackgroundColor(
                    if (isSelected) colorSelectionBg else android.graphics.Color.TRANSPARENT
                )
                root.strokeWidth = if (isSelected) 4 else 1
                root.strokeColor = if (isSelected) colorSelectionStroke else colorOutlineVariant
        
                // --- 6. Quick Action Status Toggles ---
                if (effectiveItem.isPinned) {
                    pinIcon.setImageResource(R.drawable.baseline_push_pin)
                    pinIcon.setColorFilter(colorPinActive)
                } else {
                    pinIcon.setImageResource(R.drawable.outline_push_pin)
                    pinIcon.setColorFilter(colorOnSurfaceVar)
                }
        
                favoriteIcon.setImageResource(
                    if (effectiveItem.isFavorite) android.R.drawable.btn_star_big_on
                    else android.R.drawable.btn_star_big_off
                )
                favoriteIcon.setColorFilter(
                    if (effectiveItem.isFavorite) colorPinActive else colorOnSurfaceVar
                )
        
                chipSensitive.isChecked = effectiveItem.isSensitive
                if (displayConfig.showLabel) {
                    labelText.visibility    = View.VISIBLE
                    btnEditLabel.visibility = View.VISIBLE
                    labelText.text          = item.label ?: ""
                } else {
                    labelText.visibility    = View.GONE
                    btnEditLabel.visibility = View.GONE
                }
        
                if (effectiveItem.isSensitive) {
                    eyeIcon.visibility = View.VISIBLE
                    if (isSensitiveHidden) {
                        eyeIcon.setImageResource(R.drawable.ic_visibility)
                        eyeIcon.setColorFilter(colorOnSurfaceVar)
                    } else {
                        eyeIcon.setImageResource(R.drawable.ic_visibility_off)
                        eyeIcon.setColorFilter(colorOnSurface)
                    }
                    eyeIcon.setOnClickListener { onRevealClick(item.id) }
                } else {
                    eyeIcon.visibility = View.GONE
                }
        
                // --- 7. View Event Trigger Wire-ups ---
                root.setOnClickListener {
                    if (selectedIds.isNotEmpty()) onLongClick(item) else onExpandClick(item.id)
                }
                root.setOnLongClickListener { onLongClick(item); true }
                quickCopy.setOnClickListener { onCopyClick(item) }
        
                pinIcon.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val current = localStateOverrides[item.id] ?: item
                    localStateOverrides = localStateOverrides + (item.id to current.copy(isPinned = !current.isPinned))
                    notifyItemChanged(pos)
                    onPinClick(item)
                }
        
                favoriteIcon.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val current = localStateOverrides[item.id] ?: item
                    localStateOverrides = localStateOverrides + (item.id to current.copy(isFavorite = !current.isFavorite))
                    notifyItemChanged(pos)
                    onFavoriteClick(item)
                }
        
                btnDelete.setOnClickListener    { onDeleteClick(item) }
                btnEdit.setOnClickListener      { onEditClick(item) }
                btnEditLabel.setOnClickListener { onLabelEditClick(item) }
        
                chipSensitive.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val current = localStateOverrides[item.id] ?: item
                    localStateOverrides = localStateOverrides + (item.id to current.copy(isSensitive = !current.isSensitive))
                    notifyItemChanged(pos)
                    onSensitiveClick(item)
                }
            }
        }
        
        fun bindState(item: ClipboardItem) {
            val effectiveItem = run {
                val ov = localStateOverrides[item.id]
                if (ov != null &&
                    ov.isPinned    == item.isPinned    &&
                    ov.isFavorite  == item.isFavorite  &&
                    ov.isSensitive == item.isSensitive) {
                    localStateOverrides = localStateOverrides - item.id
                    item
                } else {
                    ov ?: item
                }
            }

            val ctx              = binding.root.context
            val colorOnSurface   = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
            val colorOnSurfaceVar= MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val colorPinActive   = ContextCompat.getColor(ctx, R.color.color_pin_active)
            val isRevealed       = revealedIds.contains(item.id)

            if (effectiveItem.isPinned) {
                binding.pinIcon.setImageResource(R.drawable.baseline_push_pin)
                binding.pinIcon.setColorFilter(colorPinActive)
            } else {
                binding.pinIcon.setImageResource(R.drawable.outline_push_pin)
                binding.pinIcon.setColorFilter(colorOnSurfaceVar)
            }

            binding.favoriteIcon.setImageResource(
                if (effectiveItem.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.favoriteIcon.setColorFilter(
                if (effectiveItem.isFavorite) colorPinActive else colorOnSurfaceVar
            )

            binding.chipSensitive.isChecked = effectiveItem.isSensitive

            if (effectiveItem.isSensitive) {
                binding.eyeIcon.visibility = View.VISIBLE
                if (isRevealed) {
                    binding.eyeIcon.setImageResource(R.drawable.ic_visibility_off)
                    binding.eyeIcon.setColorFilter(colorOnSurface)
                } else {
                    binding.eyeIcon.setImageResource(R.drawable.ic_visibility)
                    binding.eyeIcon.setColorFilter(colorOnSurfaceVar)
                }
            } else {
                binding.eyeIcon.visibility = View.GONE
            }
        }

        // --- Low-Level View Helpers & Utility Methods ---

        private fun loadImageSafely(imageView: android.widget.ImageView, filePath: String) {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    imageView.setImageDrawable(null)
                    return
                }
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(filePath, options)
        
                options.inSampleSize = calculateInSampleSize(options, reqWidth = 800, reqHeight = 600)
                options.inJustDecodeBounds = false
        
                val bitmap = android.graphics.BitmapFactory.decodeFile(filePath, options)
                imageView.setImageBitmap(bitmap)
        
            } catch (e: Exception) {
                android.util.Log.e("ClipboardAdapter", "Failed to load image: $filePath", e)
                imageView.setImageDrawable(null)
            }
        }
        
        private fun calculateInSampleSize(
            options: android.graphics.BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val height = options.outHeight
            val width  = options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth  = width  / 2
                while (halfHeight / inSampleSize >= reqHeight
                    && halfWidth  / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        private fun Int.toPx(context: android.content.Context): Int {
            return (this * context.resources.displayMetrics.density).toInt()
        }
    }

    // =====================================================================
    // 5. Companion Evaluation Objects (DiffUtil Callback)
    // =====================================================================

    companion object DiffCallback : DiffUtil.ItemCallback<ClipboardItem>() {
        override fun areItemsTheSame(oldItem: ClipboardItem, newItem: ClipboardItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ClipboardItem, newItem: ClipboardItem): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: ClipboardItem, newItem: ClipboardItem): Any? {
            val contentUnchanged =
                oldItem.clip      == newItem.clip      &&
                oldItem.filePath  == newItem.filePath  &&
                oldItem.timestamp == newItem.timestamp &&
                oldItem.appName   == newItem.appName   &&
                oldItem.label     == newItem.label
            return if (contentUnchanged) "STATE_CHANGE" else null
        }
    }
}
