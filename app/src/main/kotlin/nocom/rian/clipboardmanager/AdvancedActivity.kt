package nocom.rian.clipboardmanager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nocom.rian.clipboardmanager.databinding.ActivityAdvancedBinding
import nocom.rian.clipboardmanager.db.AppDatabase
import nocom.rian.clipboardmanager.db.AutomationRule
import nocom.rian.clipboardmanager.db.loadRules
import nocom.rian.clipboardmanager.db.saveRules

class AdvancedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedBinding
    private lateinit var prefs: SharedPreferences

    // Track views dynamically created inside the ViewPager pages
    private var sqlView: View? = null
    private var rulesView: View? = null
    private var intentView: View? = null
    companion object {
        const val KEY_INTENT_SENDER_ENABLED = "intent_sender_enabled"
        const val KEY_INTENT_SENDER_ACTION = "intent_sender_action"
        const val KEY_INTENT_SENDER_PACKAGE = "intent_sender_package"
        const val KEY_INTENT_SENDER_EXTRA_KEY = "intent_sender_extra_key"
        const val KEY_INTENT_SENDER_ON_UPDATE = "intent_sender_on_update"
    }
    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.advancedToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // Attach the ViewPager adapter and disable offscreen page discarding 
        binding.advancedViewPager.adapter = AdvancedPagerAdapter()
        binding.advancedViewPager.offscreenPageLimit = 2

        // Bind TabLayout and ViewPager2 tabs together
        TabLayoutMediator(binding.advancedTabs, binding.advancedViewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Automation Rules"
                1 -> "Intent Sender"
                else -> "SQL Console"
            }
        }.attach()
    }

    override fun onSupportNavigateUp(): Boolean { 
        finish()
        return true 
    }

    // =========================================================================
    // ViewPager2 Pager Adapter
    // =========================================================================

    private inner class AdvancedPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = 3
        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> {
                    val v = inflater.inflate(R.layout.layout_advanced_rules, parent, false)
                    rulesView = v
                    RulesViewHolder(v)
                }
                1 -> {
                    val v = inflater.inflate(R.layout.layout_advanced_intent, parent, false)
                    intentView = v
                    IntentViewHolder(v)
                }
                else -> {
                    val v = inflater.inflate(R.layout.layout_advanced_sql, parent, false)
                    sqlView = v
                    SqlViewHolder(v)
                }
            }
            return view
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                0 -> (holder as RulesViewHolder).bind()
                1 -> (holder as IntentViewHolder).bind()
                2 -> (holder as SqlViewHolder).bind()
            }
        }
    }
    
    // =========================================================================
    // Intent Sender Section View Holder
    // =========================================================================

    private inner class IntentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val switchIntentSender = itemView.findViewById<MaterialSwitch>(R.id.switchIntentSender)
        private val layoutFields = itemView.findViewById<LinearLayout>(R.id.layoutIntentSettingsFields)
        private val etIntentAction = itemView.findViewById<TextInputEditText>(R.id.etIntentAction)
        private val etIntentPackage = itemView.findViewById<TextInputEditText>(R.id.etIntentPackage)
        private val btnSelectTargetApp = itemView.findViewById<Button>(R.id.btnSelectTargetApp)
        private val etIntentExtraKey = itemView.findViewById<TextInputEditText>(R.id.etIntentExtraKey)
        private val btnSaveIntentSettings = itemView.findViewById<Button>(R.id.btnSaveIntentSettings)
        private val switchIntentOnUpdate = itemView.findViewById<MaterialSwitch>(R.id.switchIntentOnUpdate)

        fun bind() {
            val initialEnabled = prefs.getBoolean(KEY_INTENT_SENDER_ENABLED, false)
            switchIntentSender?.isChecked = initialEnabled
            updateFieldsEnabled(initialEnabled)

            etIntentAction?.setText(prefs.getString(KEY_INTENT_SENDER_ACTION, ""))
            etIntentPackage?.setText(prefs.getString(KEY_INTENT_SENDER_PACKAGE, ""))
            etIntentExtraKey?.setText(prefs.getString(KEY_INTENT_SENDER_EXTRA_KEY, ""))
            switchIntentOnUpdate?.isChecked = prefs.getBoolean(KEY_INTENT_SENDER_ON_UPDATE, false)

            switchIntentSender?.setOnCheckedChangeListener { _, isChecked ->
                updateFieldsEnabled(isChecked)
            }

            btnSelectTargetApp?.setOnClickListener {
                showAppPickerDialog()
            }

            btnSaveIntentSettings?.setOnClickListener {
                val isEnabled = switchIntentSender?.isChecked == true
                val action = etIntentAction?.text?.toString()?.trim() ?: ""
                val pkg = etIntentPackage?.text?.toString()?.trim() ?: ""
                val extraKey = etIntentExtraKey?.text?.toString()?.trim() ?: ""
                val isOnUpdateEnabled = switchIntentOnUpdate?.isChecked == true

                if (isEnabled && action.isEmpty()) {
                    Toast.makeText(this@AdvancedActivity, "Intent Action is required when enabled", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (isEnabled && pkg.isEmpty()) {
                    Toast.makeText(this@AdvancedActivity, "At least one Target App must be selected when enabled", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (isEnabled && extraKey.isEmpty()) {
                    Toast.makeText(this@AdvancedActivity, "Extra Key is required when enabled", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().apply {
                    putBoolean(KEY_INTENT_SENDER_ENABLED, isEnabled)
                    putString(KEY_INTENT_SENDER_ACTION, action)
                    putString(KEY_INTENT_SENDER_PACKAGE, pkg)
                    putString(KEY_INTENT_SENDER_EXTRA_KEY, extraKey)
                    putBoolean(KEY_INTENT_SENDER_ON_UPDATE, isOnUpdateEnabled)
                    apply()
                }
                Toast.makeText(this@AdvancedActivity, "Intent settings saved", Toast.LENGTH_SHORT).show()
            }
        }

        private fun updateFieldsEnabled(enabled: Boolean) {
            layoutFields?.let { container ->
                container.alpha = if (enabled) 1.0f else 0.5f
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    child.isEnabled = enabled
                    if (child is ViewGroup) {
                        enableViewGroup(child, enabled)
                    }
                }
            }
        }

        private fun enableViewGroup(viewGroup: ViewGroup, enabled: Boolean) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                child.isEnabled = enabled
                if (child is ViewGroup) {
                    enableViewGroup(child, enabled)
                }
            }
        }

        private fun showAppPickerDialog() {
            lifecycleScope.launch(Dispatchers.IO) {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                val apps = resolveInfos.map {
                    Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName)
                }.distinctBy { it.second }.sortedBy { it.first.lowercase() }

                withContext(Dispatchers.Main) {
                    val currentText = etIntentPackage?.text?.toString() ?: ""
                    val currentPackages = currentText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    
                    val appNames = apps.map { it.first }.toTypedArray()
                    val checkedItems = BooleanArray(apps.size) { i ->
                        currentPackages.contains(apps[i].second)
                    }

                    AlertDialog.Builder(this@AdvancedActivity)
                        .setTitle("Select Target Apps")
                        .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                            checkedItems[which] = isChecked
                        }
                        .setPositiveButton("OK") { _, _ ->
                            val selectedPackages = apps.filterIndexed { index, _ -> checkedItems[index] }
                                .map { it.second }
                            etIntentPackage?.setText(selectedPackages.joinToString(", "))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    // =========================================================================
    // SQL Section View Holder
    // =========================================================================

    private inner class SqlViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chipGroup = itemView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupPresets)
        private val chipAddCustom = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipAddCustom)
        private val sqlInput = itemView.findViewById<TextInputEditText>(R.id.sqlInput)
        private val btnExecuteSql = itemView.findViewById<Button>(R.id.btnExecuteSql)
        private val btnClearSql = itemView.findViewById<Button>(R.id.btnClearSql)
        private val tvSqlStatus = itemView.findViewById<TextView>(R.id.tvSqlStatus)
        private val tvSqlResults = itemView.findViewById<TextView>(R.id.tvSqlResults)

        private val CUSTOM_QUERIES_PREF_KEY = "custom_sql_pills"

        fun bind() {
            setupStaticPresets()
            loadAndBindCustomChips()

            chipAddCustom?.setOnClickListener {
                showAddCustomPillDialog()
            }

            btnExecuteSql?.setOnClickListener {
                val sql = sqlInput?.text?.toString()?.trim() ?: ""
                if (sql.isBlank()) {
                    tvSqlStatus?.text = "Enter a SQL query first."
                    return@setOnClickListener
                }
                executeQuery(sql, tvSqlStatus, tvSqlResults, btnExecuteSql)
            }

            btnClearSql?.setOnClickListener {
                sqlInput?.text?.clear()
                tvSqlResults?.visibility = View.GONE
                tvSqlStatus?.text = "No query run yet."
            }
        }

        private fun setupStaticPresets() {
            val presets = mapOf(
                itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSelectAll) to "SELECT id, clip, appName, packageName, timestamp FROM clipboardmanager ORDER BY timestamp DESC LIMIT 20",
                itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipCount) to "SELECT COUNT(*) as total,\n  COUNT(CASE WHEN is_pinned=1 THEN 1 END) as pinned,\n  COUNT(CASE WHEN is_sensitive=1 THEN 1 END) as sensitive,\n  COUNT(CASE WHEN is_favorite=1 THEN 1 END) as favorites\nFROM clipboardmanager",
                itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSensitiveQuery) to "SELECT id, clip, appName, packageName FROM clipboardmanager WHERE is_sensitive=1 ORDER BY timestamp DESC LIMIT 20",
                itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipPinnedQuery) to "SELECT id, clip, appName, label FROM clipboardmanager WHERE is_pinned=1 ORDER BY timestamp DESC",
                itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSchema) to "PRAGMA table_info(clipboardmanager)",
                itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipByApp) to "SELECT packageName, COUNT(*) as clips FROM clipboardmanager GROUP BY packageName ORDER BY clips DESC LIMIT 20"
            )

            presets.forEach { (chip, query) ->
                chip?.setOnClickListener { sqlInput?.setText(query) }
            }
        }

        private fun loadAndBindCustomChips() {
            val viewsToRemove = mutableListOf<View>()
            for (i in 0 until (chipGroup?.childCount ?: 0)) {
                val child = chipGroup?.getChildAt(i)
                if (child != null && child.id != R.id.chipAddCustom && child.tag == "CUSTOM_PILL") {
                    viewsToRemove.add(child)
                }
            }
            viewsToRemove.forEach { chipGroup?.removeView(it) }

            val savedSet = prefs.getStringSet(CUSTOM_QUERIES_PREF_KEY, emptySet()) ?: emptySet()
            
            savedSet.forEach { packedString ->
                val parts = packedString.split("|||", limit = 2)
                if (parts.size == 2) {
                    val label = parts[0]
                    val query = parts[1]

                    // APPROACH: Pass style explicitly via R.style to guarantee standard M3 suggestion look safely
                    // 1. Wrap the context with the specific Material 3 Suggestion Chip style
                    val themedContext = android.view.ContextThemeWrapper(
                        this@AdvancedActivity, 
                        com.google.android.material.R.style.Widget_Material3_Chip_Suggestion
                    )
                    
                    // 2. Instantiate the Chip using the wrapped context
                    val customChip = com.google.android.material.chip.Chip(themedContext).apply {
                        text = label
                        tag = "CUSTOM_PILL"
                        setEnsureMinTouchTargetSize(true)
                        
                        setOnLongClickListener {
                            AlertDialog.Builder(this@AdvancedActivity)
                                .setTitle("Delete Custom Pill")
                                .setMessage("Do you want to delete the \"$label\" shortcut?")
                                .setPositiveButton("Delete") { _, _ ->
                                    val currentSet = prefs.getStringSet(CUSTOM_QUERIES_PREF_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
                                    currentSet.remove(packedString)
                                    prefs.edit().putStringSet(CUSTOM_QUERIES_PREF_KEY, currentSet).apply()
                                    loadAndBindCustomChips()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                        
                        setOnClickListener {
                            sqlInput?.setText(query)
                        }
                    }
                    val targetIndex = chipGroup?.indexOfChild(chipAddCustom) ?: 0
                    chipGroup?.addView(customChip, targetIndex)
                }
            }
        }

        private fun showAddCustomPillDialog() {
            val dp = resources.displayMetrics.density
            val container = LinearLayout(this@AdvancedActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            }

            val labelTil = TextInputLayout(this@AdvancedActivity, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = "Pill Title (e.g., Delete Old Clips)"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (10 * dp).toInt()
                }
            }
            val labelEditText = TextInputEditText(labelTil.context)
            labelTil.addView(labelEditText)

            val queryTil = TextInputLayout(this@AdvancedActivity, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = "SQL Script String"
            }
            val queryEditText = TextInputEditText(queryTil.context).apply {
                typeface = Typeface.MONOSPACE
                minLines = 2
            }
            queryTil.addView(queryEditText)

            container.addView(labelTil)
            container.addView(queryTil)

            AlertDialog.Builder(this@AdvancedActivity)
                .setTitle("Create Custom SQL Pill")
                .setView(container)
                .setPositiveButton("Save Blueprint") { dialog, _ ->
                    val titleText = labelEditText.text?.toString()?.trim() ?: ""
                    val queryText = queryEditText.text?.toString()?.trim() ?: ""

                    if (titleText.isBlank() || queryText.isBlank()) {
                        Toast.makeText(this@AdvancedActivity, "Both fields are mandatory!", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val currentSet = prefs.getStringSet(CUSTOM_QUERIES_PREF_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
                    currentSet.add("$titleText|||$queryText")
                    prefs.edit().putStringSet(CUSTOM_QUERIES_PREF_KEY, currentSet).apply()

                    loadAndBindCustomChips()
                    dialog.dismiss()
                }
                .setNegativeButton("Dismiss", null)
                .show()
        }
    }

    private fun executeQuery(sql: String, tvStatus: TextView?, tvResults: TextView?, btnExecute: Button?) {
        tvStatus?.text = "Running…"
        tvResults?.visibility = View.GONE
        btnExecute?.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val db = AppDatabase.getDatabase(applicationContext).openHelper.writableDatabase
                val upper = sql.trim().uppercase()

                if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("WITH")) {
                    val cursor = db.query(sql, emptyArray())
                    val cols = cursor.columnNames
                    val widths = cols.map { it.length }.toIntArray()
                    val rows = mutableListOf<Array<String>>()

                    while (cursor.moveToNext()) {
                        val row = Array(cols.size) { i ->
                            (when (cursor.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                android.database.Cursor.FIELD_TYPE_BLOB -> "[BLOB]"
                                else -> cursor.getString(i) ?: "NULL"
                            }).take(50)
                        }
                        rows.add(row)
                        row.forEachIndexed { i, v -> if (v.length > widths[i]) widths[i] = v.length }
                    }
                    cursor.close()

                    val sb = StringBuilder()
                    sb.append(cols.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString(" | "))
                    sb.append("\n")
                    sb.append(widths.joinToString("-+-") { "-".repeat(it) })
                    sb.append("\n")
                    
                    rows.forEach { row ->
                        sb.append(row.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString(" | "))
                        sb.append("\n")
                    }
                    if (rows.isEmpty()) sb.append("(no rows returned)")

                    Pair("${rows.size} row(s) returned.", sb.toString())
                } else {
                    db.execSQL(sql)
                    Pair("Query executed successfully.", "")
                }
            }

            withContext(Dispatchers.Main) {
                btnExecute?.isEnabled = true
                result.fold(
                    onSuccess = { (status, table) ->
                        tvStatus?.text = status
                        if (table.isNotBlank()) {
                            tvResults?.text = table
                            tvResults?.visibility = View.VISIBLE
                        }
                    },
                    onFailure = { e ->
                        tvStatus?.text = "⚠ Error: ${e.message}"
                        tvResults?.visibility = View.GONE
                    }
                )
            }
        }
    }

    // =========================================================================
    // Automation Rules Section View Holder
    // =========================================================================

    private inner class RulesViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnAddRule = itemView.findViewById<Button>(R.id.btnAddRule)

        fun bind() {
            btnAddRule?.setOnClickListener { showRuleDialog(null) }
            refreshRulesList()
        }
    }

    private fun refreshRulesList() {
        val view = rulesView ?: return
        val rulesContainer = view.findViewById<RecyclerView>(R.id.rulesContainer) ?: return
        val tvNoRules = view.findViewById<TextView>(R.id.tvNoRules) ?: return
        
        // Grab the parent NestedScrollView so we can force it to scroll
        val scrollView = view as? androidx.core.widget.NestedScrollView ?: return

        val rules = loadRules(prefs).toMutableList()
        tvNoRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

        val adapter = rulesContainer.adapter as? RulesAdapter
        if (adapter == null) {
            val newAdapter = RulesAdapter(rules)
            rulesContainer.adapter = newAdapter

            // --- Auto-scroll Engine ---
            val scrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
            var currentScrollAmount = 0
            val scrollRunnable = object : Runnable {
                override fun run() {
                    if (currentScrollAmount != 0) {
                        scrollView.scrollBy(0, currentScrollAmount)
                        scrollHandler.postDelayed(this, 16L) // Run at ~60fps for smooth scrolling
                    }
                }
            }

            // Attach the Drag-and-Drop Helper
            val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
                    
                    val currentAdapter = recyclerView.adapter as? RulesAdapter ?: return false
                    java.util.Collections.swap(currentAdapter.rules, fromPos, toPos)
                    currentAdapter.notifyItemMoved(fromPos, toPos)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    
                    // Stop scrolling instantly when the item is dropped
                    currentScrollAmount = 0
                    scrollHandler.removeCallbacks(scrollRunnable)
                    
                    // Save the new execution order to disk
                    val currentAdapter = recyclerView.adapter as? RulesAdapter
                    if (currentAdapter != null) {
                        saveRules(prefs, currentAdapter.rules)
                    }
                }

                // Inject our custom scrolling logic while the item is being dragged
                override fun onChildDraw(
                    c: android.graphics.Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

                    if (isCurrentlyActive && actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        // 1. Get where the dragged card is on the physical screen
                        val location = IntArray(2)
                        viewHolder.itemView.getLocationOnScreen(location)
                        val viewY = location[1]
                        val viewHeight = viewHolder.itemView.height

                        // 2. Get where the ScrollView is on the physical screen
                        val scrollLocation = IntArray(2)
                        scrollView.getLocationOnScreen(scrollLocation)
                        val scrollY = scrollLocation[1]
                        val scrollHeight = scrollView.height

                        // 3. Define the scroll trigger zones (e.g., within 80dp of the edge)
                        val dp = resources.displayMetrics.density
                        val threshold = (80 * dp).toInt() 
                        val scrollSpeed = (8 * dp).toInt() 
                        
                        val topBoundary = scrollY + threshold
                        val bottomBoundary = scrollY + scrollHeight - threshold

                        // 4. Decide which direction to scroll based on card location
                        val newScrollAmount = when {
                            viewY < topBoundary -> -scrollSpeed         // Scroll Up
                            viewY + viewHeight > bottomBoundary -> scrollSpeed  // Scroll Down
                            else -> 0
                        }

                        // 5. Start or stop the 60fps loop if the direction changed
                        if (newScrollAmount != currentScrollAmount) {
                            currentScrollAmount = newScrollAmount
                            if (currentScrollAmount != 0) {
                                scrollHandler.post(scrollRunnable)
                            } else {
                                scrollHandler.removeCallbacks(scrollRunnable)
                            }
                        }
                    }
                }
            })
            touchHelper.attachToRecyclerView(rulesContainer)
            
        } else {
            adapter.rules = rules
            adapter.notifyDataSetChanged()
        }
    }



    // Wrap the programmatic cards into a RecyclerView Adapter
        // =========================================================================
    // Optimized Rules Adapter
    // =========================================================================

    private inner class RulesAdapter(var rules: MutableList<AutomationRule>) : RecyclerView.Adapter<RulesAdapter.ViewHolder>() {

        inner class ViewHolder(
            val container: FrameLayout,
            val cardView: MaterialCardView,
            val nameText: TextView,
            val enableSwitch: MaterialSwitch,
            val collapsibleContainer: LinearLayout,
            val condText: TextView,
            val actionText: TextView,
            val editBtn: Button,
            val deleteBtn: Button
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val dp = resources.displayMetrics.density
            val pad = (12 * dp).toInt()

            val frame = FrameLayout(this@AdvancedActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val card = MaterialCardView(this@AdvancedActivity).apply {
                radius = 12 * dp
                strokeWidth = 1
                setStrokeColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_outlineVariant))
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_surface))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }

            val inner = LinearLayout(this@AdvancedActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, (8 * dp).toInt())
            }

            val row1 = LinearLayout(this@AdvancedActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameText = TextView(this@AdvancedActivity).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_onSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val enableSwitch = MaterialSwitch(this@AdvancedActivity)

            row1.addView(nameText)
            row1.addView(enableSwitch)

            val collapsibleContainer = LinearLayout(this@AdvancedActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val condText = TextView(this@AdvancedActivity).apply {
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_onSurfaceVariant))
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }

            val actionText = TextView(this@AdvancedActivity).apply {
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_primary))
                setPadding(0, (2 * dp).toInt(), 0, 0)
            }

            val btnRow = LinearLayout(this@AdvancedActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            val editBtn = Button(this@AdvancedActivity).apply {
                text = "Edit"
                isAllCaps = false
                background = null
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (4 * dp).toInt() }
            }
            val deleteBtn = Button(this@AdvancedActivity).apply {
                text = "Delete"
                isAllCaps = false
                background = null
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_error))
            }
            
            btnRow.addView(editBtn)
            btnRow.addView(deleteBtn)

            collapsibleContainer.addView(condText)
            collapsibleContainer.addView(actionText)
            collapsibleContainer.addView(btnRow)

            inner.addView(row1)
            inner.addView(collapsibleContainer)
            card.addView(inner)
            frame.addView(card)

            return ViewHolder(frame, card, nameText, enableSwitch, collapsibleContainer, condText, actionText, editBtn, deleteBtn)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val rule = rules[position]
            val dp = resources.displayMetrics.density
            val pad = (12 * dp).toInt()

            holder.nameText.text = rule.name.ifBlank { "Unnamed Rule" }
            holder.condText.text = buildConditionSummary(rule)
            holder.actionText.text = buildActionSummary(rule)

            // Remove listener temporarily so programmatic changes don't trigger saves
            holder.enableSwitch.setOnCheckedChangeListener(null)
            holder.enableSwitch.isChecked = rule.enabled

            // Reset collapsible container state without animating
            if (!rule.enabled) {
                holder.collapsibleContainer.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0
                )
                holder.collapsibleContainer.visibility = View.INVISIBLE
            } else {
                holder.collapsibleContainer.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                holder.collapsibleContainer.visibility = View.VISIBLE
            }

            holder.enableSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    holder.collapsibleContainer.visibility = View.VISIBLE
                    holder.collapsibleContainer.measure(
                        View.MeasureSpec.makeMeasureSpec((holder.cardView.width - (2 * pad)), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val targetHeight = holder.collapsibleContainer.measuredHeight

                    android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                        duration = 250
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener { animator ->
                            holder.collapsibleContainer.layoutParams = holder.collapsibleContainer.layoutParams.apply {
                                height = animator.animatedValue as Int
                            }
                        }
                        start()
                    }
                } else {
                    val initialHeight = holder.collapsibleContainer.height
                    android.animation.ValueAnimator.ofInt(initialHeight, 0).apply {
                        duration = 250
                        interpolator = android.view.animation.AccelerateInterpolator()
                        addUpdateListener { animator ->
                            holder.collapsibleContainer.layoutParams = holder.collapsibleContainer.layoutParams.apply {
                                height = animator.animatedValue as Int
                            }
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                if (!holder.enableSwitch.isChecked) holder.collapsibleContainer.visibility = View.INVISIBLE
                            }
                        })
                        start()
                    }
                }

                // Persist state to Preferences
                val list = loadRules(prefs).toMutableList()
                val idx = list.indexOfFirst { it.id == rule.id }
                if (idx >= 0) { 
                    list[idx] = list[idx].copy(enabled = checked)
                    saveRules(prefs, list) 
                }
            }

            holder.editBtn.setOnClickListener { showRuleDialog(rule) }
            
            holder.deleteBtn.setOnClickListener {
                AlertDialog.Builder(this@AdvancedActivity)
                    .setTitle("Delete Rule")
                    .setMessage("Delete \"${rule.name}\"? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        saveRules(prefs, loadRules(prefs).filter { it.id != rule.id })
                        refreshRulesList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount() = rules.size
    }

    private fun buildConditionSummary(rule: AutomationRule): String {
        val parts = mutableListOf<String>()
        if (rule.conditionContentType != "any") parts.add("type = ${rule.conditionContentType}")
        if (rule.conditionSourcePackage.isNotBlank()) parts.add("package contains \"${rule.conditionSourcePackage}\"")
        if (rule.conditionAppName.isNotBlank()) parts.add("app name contains \"${rule.conditionAppName}\"")
        if (rule.conditionPreAppName.isNotBlank()) parts.add("previous app name contains \"${rule.conditionPreAppName}\"")
        if (rule.conditionSourceWindowLabel.isNotBlank()) parts.add("window label contains \"${rule.conditionSourceWindowLabel}\"")
        if (rule.conditionPreSourceWindowLabel.isNotBlank()) parts.add("previous window label contains \"${rule.conditionPreSourceWindowLabel}\"")
        if (rule.conditionContentPattern.isNotBlank()) parts.add("content ~ /${rule.conditionContentPattern}/")
        return if (parts.isEmpty()) "IF: any clip" else "IF: ${parts.joinToString(" · ")}"
    }

    private fun buildActionSummary(rule: AutomationRule): String {
        val parts = mutableListOf<String>()
        if (rule.actionLabel.isNotBlank()) parts.add("label → \"${rule.actionLabel}\"")
        when (rule.actionSensitive) { 1 -> parts.add("mark sensitive"); 0 -> parts.add("clear sensitive") }
        when (rule.actionFavorite)  { 1 -> parts.add("mark favorite"); 0 -> parts.add("clear favorite") }
        when (rule.actionPinned)    { 1 -> parts.add("pin"); 0 -> parts.add("unpin") }
        return if (parts.isEmpty()) "THEN: (no actions)" else "THEN: ${parts.joinToString(" · ")}"
    }

    // =========================================================================
    // Add / Edit Rule Dialog
    // =========================================================================

    private fun showRuleDialog(existing: AutomationRule?) {
        val isNew = existing == null
        val dp = resources.displayMetrics.density

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }

        fun sectionLabel(text: String) = rootLayout.addView(TextView(this).apply {
            this.text = text
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_primary))
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            isAllCaps = true
        })

        fun addEditField(hint: String, defaultText: String = ""): TextInputEditText {
            val til = TextInputLayout(
                this, null, com.google.android.material.R.attr.textInputOutlinedStyle
            ).apply {
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            val et = TextInputEditText(til.context).apply { setText(defaultText) }
            til.addView(et)
            rootLayout.addView(til)
            return et
        }

        fun addSpinner(label: String, options: Array<String>, selectedIndex: Int): Spinner {
            rootLayout.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_onSurfaceVariant))
                setPadding(0, (4 * dp).toInt(), 0, (2 * dp).toInt())
            })
            return Spinner(this).also { spinner ->
                spinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, options
                )
                spinner.setSelection(selectedIndex.coerceAtLeast(0))
                spinner.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * dp).toInt() }
                rootLayout.addView(spinner)
            }
        }

        sectionLabel("Rule")
        val nameEdit = addEditField("Rule name (required)", existing?.name ?: "")

        sectionLabel("Conditions  —  leave blank to match any clip")
        val appNameEdit = addEditField("App name contains  (e.g. Chrome, WhatsApp, Firefox)", existing?.conditionAppName ?: "")
        val packageEdit = addEditField("Package name contains  (e.g. com.whatsapp, firefox)", existing?.conditionSourcePackage ?: "")
        val preAppNameEdit = addEditField("Previous App name contains  (e.g. Chrome, Google, Firefox)", existing?.conditionPreAppName ?: "")
        val windowLabelEdit = addEditField("Window Label name contains  (e.g. Gemini, um idk)", existing?.conditionSourceWindowLabel ?: "")
        val preWindowLabelEdit = addEditField("Previous Window Label contains  (e.g. Chrome, Gemini, Firefox)", existing?.conditionPreSourceWindowLabel ?: "")
        val patternEdit = addEditField("Content regex  (e.g. \\d{6} for OTP, https?:// for URLs)", existing?.conditionContentPattern ?: "")
        
        val typeValues  = arrayOf("any", "text", "image")
        val typeOptions = arrayOf("Any clip", "Text only", "Image only")
        val typeSpinner = addSpinner(
            "Content type:",
            typeOptions,
            typeValues.indexOf(existing?.conditionContentType ?: "any").coerceAtLeast(0)
        )

        sectionLabel("Actions  —  leave blank / \"Don't change\" to skip")
        val labelEdit = addEditField("Assign label  (e.g. OTP, Password, URL)", existing?.actionLabel ?: "")
        
        val toggleValues  = intArrayOf(-1, 1, 0)
        val sensSpinner = addSpinner(
            "Sensitive flag:",
            arrayOf("Don't change", "Mark as sensitive", "Remove sensitive"),
            toggleValues.indexOf(existing?.actionSensitive ?: -1).coerceAtLeast(0)
        )
        
        val favSpinner = addSpinner(
            "Favorite flag:",
            arrayOf("Don't change", "Mark as favorite", "Remove favorite"),
            toggleValues.indexOf(existing?.actionFavorite ?: -1).coerceAtLeast(0)
        )
        
        val pinnedSpinner = addSpinner(
            "Pinned state:",
            arrayOf("Don't change", "Pin the clip", "Unpin the clip"),
            toggleValues.indexOf(existing?.actionPinned ?: -1).coerceAtLeast(0)
        )

        val scrollView = ScrollView(this).apply { addView(rootLayout) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Add Automation Rule" else "Edit Rule")
            .setView(scrollView)
            .setPositiveButton(if (isNew) "Add Rule" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEdit.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                Toast.makeText(this, "Rule name cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val rule = AutomationRule(
                id                         = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name                       = name,
                enabled                    = existing?.enabled ?: true,
                conditionAppName           = appNameEdit.text?.toString()?.trim() ?: "",
                conditionSourcePackage     = packageEdit.text?.toString()?.trim() ?: "",
                conditionPreAppName        = preAppNameEdit.text?.toString()?.trim() ?: "",
                conditionSourceWindowLabel = windowLabelEdit.text?.toString()?.trim() ?: "",
                conditionPreSourceWindowLabel = preWindowLabelEdit.text?.toString()?.trim() ?: "",
                conditionContentPattern    = patternEdit.text?.toString()?.trim() ?: "",
                conditionContentType       = typeValues[typeSpinner.selectedItemPosition],
                actionLabel                = labelEdit.text?.toString()?.trim() ?: "",
                actionSensitive            = toggleValues[sensSpinner.selectedItemPosition],
                actionFavorite             = toggleValues[favSpinner.selectedItemPosition],
                actionPinned               = toggleValues[pinnedSpinner.selectedItemPosition]
            )
            
            val list = loadRules(prefs).toMutableList()
            if (isNew) {
                list.add(rule)
            } else {
                val idx = list.indexOfFirst { it.id == rule.id }
                if (idx >= 0) list[idx] = rule else list.add(rule)
            }
            
            saveRules(prefs, list)
            refreshRulesList()
            dialog.dismiss()
        }
    }
}
