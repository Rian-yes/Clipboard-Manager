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
    private val onEditClick: (ClipboardItem) -> Unit,
    private val initialConfig: ClipDisplayConfig = ClipDisplayConfig()
) : ListAdapter<ClipboardItem, ClipboardAdapter.ClipboardViewHolder>(DiffCallback) {

    private var expandedIds: Set<Int> = emptySet()
    private val pendingAnimationIds: MutableSet<Int> = mutableSetOf()
    private var localStateOverrides: Map<Int, ClipboardItem> = emptyMap()
    private var revealedIds: Set<Int> = emptySet()
    private var selectedIds: Set<Int> = emptySet()
    private var displayConfig: ClipDisplayConfig = initialConfig

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

    private fun animateExpansion(binding: ItemClipboardBinding, expand: Boolean) {
        val view = binding.detailContainer
        val arrow = binding.expandArrow

        (view.tag as? android.animation.ValueAnimator)?.cancel()
        arrow.animate().cancel()

        if (expand) {
            view.visibility = View.VISIBLE
            view.measure(
                View.MeasureSpec.makeMeasureSpec(binding.root.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = view.measuredHeight

            android.animation.ValueAnimator.ofInt(view.height, targetHeight).apply {
                duration = 250
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Int
                    view.layoutParams = view.layoutParams.apply { height = value }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        view.tag = null
                        // FIX: Release the hardcoded height so it doesn't squish contents
                        view.layoutParams = view.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    }
                })
                view.tag = this
                start()
            }


            arrow.animate()
                .rotation(180f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

        } else {
            val initialHeight = view.height

            android.animation.ValueAnimator.ofInt(initialHeight, 0).apply {
                duration = 250
                interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Int
                    view.layoutParams = view.layoutParams.apply { height = value }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (view.height == 0) {
                            view.visibility = View.INVISIBLE
                        }
                        view.tag = null
                    }
                })
                view.tag = this
                start()
            }

            arrow.animate()
                .rotation(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
        }
    }

    // =====================================================================
    // 4. Inner ViewHolder Implementation
    // =====================================================================

    inner class ClipboardViewHolder(private val binding: ItemClipboardBinding) :
        RecyclerView.ViewHolder(binding.root) {
    
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
        
                val typeLabel = if (isImage) "Image" else item.appName ?: "Unknown"
                metaText.text = "$typeLabel • ${getRelativeTime(item.timestamp, item.date)}"
                metaText.setTextColor(colorOnSurfaceVar)
        
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
                
                // Word / character count — text clips only
                if (!isImage && !isSensitiveHidden && item.clip != null) {
                    val charCount = item.clip.length
                    val wordCount = item.clip.trim()
                        .split(Regex("\\s+"))
                        .count { it.isNotEmpty() }
                    binding.wordCharCount.text = "$charCount chars · $wordCount words"
                    binding.wordCharCount.visibility = View.VISIBLE
                } else {
                    binding.wordCharCount.visibility = View.GONE
                }

                // if (isSensitiveHidden && expandedIds.contains(item.id)) {
                //     onExpandClick(item.id)
                // }

                // --- 4. Content Block Expansions ---
                val canExpand = shouldExpand && !isSensitiveHidden

                // Cancel any lingering animator tags left on recycled views before managing states
                (detailContainer.tag as? android.animation.ValueAnimator)?.cancel()
                detailContainer.tag = null

                if (shouldAnimate) {
                    animateExpansion(binding, canExpand)
                } else {
                    // SYNCHRONIZED STATIC STATES:
                    if (canExpand) {
                        detailContainer.visibility = View.VISIBLE
                        // FIX: Let Android calculate the height natively instead of measuring it manually
                        detailContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        expandArrow.rotation = 180f
                    } else {
                        // If it's sensitive and hidden, ensure it collapses completely 
                        detailContainer.visibility = View.GONE 
                        detailContainer.layoutParams.height = 0 
                        expandArrow.rotation = 0f
                    }
                }


                root.isChecked = isSelected
                root.setCardBackgroundColor(
                    if (isSelected) colorSelectionBg else android.graphics.Color.TRANSPARENT
                )
                root.strokeWidth = if (isSelected) 4 else 1
                root.strokeColor = if (isSelected) colorSelectionStroke else colorOutlineVariant
        
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
                    eyeIcon.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) onRevealClick(getItem(pos).id)
                    }
                } else {
                    eyeIcon.visibility = View.GONE
                }
        
                // ── DYNAMIC EXTRACTIONS FROM THE LIST TO AVOID CAPTURING STALE VARS ──
                // --- 7. View Event Trigger Wire-ups ---
                root.setOnClickListener {
                    if (selectedIds.isNotEmpty()) {
                        onLongClick(item)
                    } else {
                        // If it's sensitive and currently hidden, clicking the card 
                        // shouldn't fire an expansion/collapse toggle at all.
                        if (isSensitiveHidden) {
                            // Optional: You can choose to reveal the item here instead:
                            // onRevealClick(item.id)
                            return@setOnClickListener
                        }
                        onExpandClick(item.id)
                    }
                }

                root.setOnLongClickListener { 
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onLongClick(getItem(pos))
                    true 
                }

                quickCopy.setOnClickListener { 
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onCopyClick(getItem(pos))
                }
        
                pinIcon.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val freshItem = getItem(pos)
                    val currentVisual = localStateOverrides[freshItem.id] ?: freshItem
                    
                    val updated = currentVisual.copy(isPinned = !currentVisual.isPinned)
                    localStateOverrides = localStateOverrides + (freshItem.id to updated)
                    bindState(freshItem) // instant UI modification update
                    onPinClick(freshItem)
                }
        
                favoriteIcon.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val freshItem = getItem(pos)
                    val currentVisual = localStateOverrides[freshItem.id] ?: freshItem
                    
                    val updated = currentVisual.copy(isFavorite = !currentVisual.isFavorite)
                    localStateOverrides = localStateOverrides + (freshItem.id to updated)
                    bindState(freshItem)
                    onFavoriteClick(freshItem)
                }
        
          chipSensitive.setOnClickListener {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val current = localStateOverrides[item.id] ?: item
            
            // If turning sensitivity ON and it's currently expanded, clear the expansion state cleanly
            if (!current.isSensitive && expandedIds.contains(item.id)) {
                onExpandClick(item.id) 
            }

            localStateOverrides = localStateOverrides + (item.id to current.copy(isSensitive = !current.isSensitive))
            notifyItemChanged(pos)
            onSensitiveClick(item)
        }


                btnDelete.setOnClickListener { 
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onDeleteClick(getItem(pos))
                }
                btnEdit.setOnClickListener { 
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onEditClick(getItem(pos))
                }
                btnEditLabel.setOnClickListener { 
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onLabelEditClick(getItem(pos))
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
    
    // =====================================================================
// 6. Private Time Formatting Helpers
// =====================================================================

private fun getRelativeTime(timestamp: Long, fallbackDate: String): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L          -> "Just now"
        diff < 3_600_000L       -> "${diff / 60_000L}m ago"
        diff < 86_400_000L      -> "${diff / 3_600_000L}h ago"
        diff < 7 * 86_400_000L  -> "${diff / 86_400_000L}d ago"
        diff < 30 * 86_400_000L -> "${diff / (7 * 86_400_000L)}w ago"
        else                    -> fallbackDate
    }
}
}
