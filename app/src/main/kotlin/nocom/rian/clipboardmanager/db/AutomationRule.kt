package nocom.rian.clipboardmanager.db

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName

class AutomationRuleListTypeToken : TypeToken<List<AutomationRule>>()

const val PREFS_KEY_AUTOMATION_RULES = "automation_rules"

/**
 * A single if-then automation rule applied to every captured clip.
 *
 * Conditions (all empty/default = match anything):
 *   conditionSourcePackage  – substring match on the source app package name
 *   conditionContentPattern – Java regex matched against the clip text
 *   conditionContentType    – "any" | "text" | "image"
 *
 * Actions (-1 = don't change for Int fields, "" = skip for String fields):
 *   actionLabel     – label string to assign
 *   actionSensitive – -1 no-op, 0 clear, 1 set
 *   actionPinned    – -1 no-op, 0 clear, 1 set
 */
 
data class AutomationRule(
    @SerializedName("id") val id: String = java.util.UUID.randomUUID().toString(),
    @SerializedName("name") val name: String = "",
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("conditionSourcePackage") val conditionSourcePackage: String = "",
    @SerializedName("conditionAppName") val conditionAppName: String = "",
    @SerializedName("conditionSourceWindowLabel") val conditionSourceWindowLabel: String = "",
    @SerializedName("conditionPreAppName") val conditionPreAppName: String = "",
    @SerializedName("conditionPreSourceWindowLabel") val conditionPreSourceWindowLabel: String = "",
    @SerializedName("conditionContentPattern") val conditionContentPattern: String = "",
    @SerializedName("conditionContentType") val conditionContentType: String = "any",
    @SerializedName("actionLabel") val actionLabel: String = "",
    @SerializedName("actionSensitive") val actionSensitive: Int = -1,
    @SerializedName("actionFavorite") val actionFavorite: Int = -1,
    @SerializedName("actionPinned") val actionPinned: Int = -1
)

fun loadRules(prefs: SharedPreferences): List<AutomationRule> {
    val json = prefs.getString(PREFS_KEY_AUTOMATION_RULES, null) ?: return emptyList()
    return try {
        // 2. Use the concrete subclass instead of an anonymous object definition
        val type = AutomationRuleListTypeToken().type
        Gson().fromJson<List<AutomationRule>>(json, type) ?: emptyList()
    } catch (e: Exception) {
        android.util.Log.e("AutomationRule", "Failed to deserialize rules: ${e.message}", e)
        emptyList()
    }
}
fun saveRules(prefs: SharedPreferences, rules: List<AutomationRule>) {
    try {
        val json = Gson().toJson(rules)
        val success = prefs.edit()
            .putString(PREFS_KEY_AUTOMATION_RULES, json)
            .commit() // commit() instead of apply() — synchronous, verifiable
        android.util.Log.d("AutomationRule", "saveRules: saved ${rules.size} rule(s), success=$success")
    } catch (e: Exception) {
        android.util.Log.e("AutomationRule", "Failed to serialize rules: ${e.message}", e)
    }
}