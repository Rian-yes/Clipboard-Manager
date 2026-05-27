package nocom.rian.clipboardmanager

import android.content.Context
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
            tab.text = if (position == 0) "SQL Console" else "Automation Rules"
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
        override fun getItemCount(): Int = 2
        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val view = inflater.inflate(R.layout.layout_advanced_sql, parent, false)
                sqlView = view
                SqlViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.layout_advanced_rules, parent, false)
                rulesView = view
                RulesViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) (holder as SqlViewHolder).bind()
            else (holder as RulesViewHolder).bind()
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
        val rulesContainer = view.findViewById<LinearLayout>(R.id.rulesContainer) ?: return
        val tvNoRules = view.findViewById<TextView>(R.id.tvNoRules) ?: return

        val rules = loadRules(prefs)
        rulesContainer.removeAllViews()
        tvNoRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        rules.forEach { rule -> rulesContainer.addView(buildRuleCard(rule)) }
    }

    private fun buildRuleCard(rule: AutomationRule): View {
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()

        val card = MaterialCardView(this).apply {
            radius = 12 * dp
            strokeWidth = 1
            setStrokeColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_outlineVariant))
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (8 * dp).toInt())
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameText = TextView(this).apply {
            text = rule.name.ifBlank { "Unnamed Rule" }
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_onSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val enableSwitch = MaterialSwitch(this).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                val list = loadRules(prefs).toMutableList()
                val idx = list.indexOfFirst { it.id == rule.id }
                if (idx >= 0) { 
                    list[idx] = list[idx].copy(enabled = checked)
                    saveRules(prefs, list) 
                }
            }
        }
        row1.addView(nameText)
        row1.addView(enableSwitch)

        val condText = TextView(this).apply {
            text = buildConditionSummary(rule)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_onSurfaceVariant))
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        val actionText = TextView(this).apply {
            text = buildActionSummary(rule)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_primary))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        val editBtn = Button(this).apply {
            text = "Edit"
            isAllCaps = false
            background = null
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (4 * dp).toInt() }
            setOnClickListener { showRuleDialog(rule) }
        }
        val deleteBtn = Button(this).apply {
            text = "Delete"
            isAllCaps = false
            background = null
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedActivity, R.color.md_theme_error))
            setOnClickListener {
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
        btnRow.addView(editBtn)
        btnRow.addView(deleteBtn)

        inner.addView(row1)
        inner.addView(condText)
        inner.addView(actionText)
        inner.addView(btnRow)
        card.addView(inner)
        return card
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
