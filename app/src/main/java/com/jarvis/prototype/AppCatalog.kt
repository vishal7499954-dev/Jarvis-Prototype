package com.jarvis.prototype

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import java.util.Locale
import kotlin.math.max

/** Local catalogue of launchable apps; package names are stable identifiers. */
object AppCatalog {
    data class InstalledApp(val label: String, val packageName: String)

    private val builtInCommands = listOf(
        "open settings", "open the settings", "open camera", "open the camera",
        "open whatsapp", "open youtube", "open chrome", "open gallery", "open photos",
        "open calculator", "open phone", "open messages", "open contacts", "open clock",
        "open play store", "open bluetooth settings", "open wi-fi settings", "go back",
        "go home", "scroll down", "scroll up", "turn on wi-fi", "turn off wi-fi",
        "turn on bluetooth", "turn off bluetooth", "volume up", "volume down",
        "take a screenshot", "what time is it", "stop listening", "[unk]"
    )

    fun scan(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .mapNotNull { info ->
                    val launchIntent = pm.getLaunchIntentForPackage(info.packageName)
                    if (launchIntent == null) null else {
                        val label = pm.getApplicationLabel(info).toString().trim()
                        if (label.isBlank()) null else InstalledApp(label, info.packageName)
                    }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.US) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun commandGrammar(context: Context): String {
        val phrases = JSONArray()
        builtInCommands.forEach { phrases.put(it) }
        val aliases = context.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE)
            .all.keys.filterIsInstance<String>()
        aliases.forEach { alias ->
            if (alias.isNotBlank()) phrases.put("open $alias")
        }
        scan(context).take(MAX_GRAMMAR_APPS).forEach { app ->
            val safeLabel = app.label.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (safeLabel.isNotBlank()) {
                phrases.put("open $safeLabel")
                if (safeLabel.contains("insta")) {
                    phrases.put("open instagram")
                    phrases.put("open insta")
                }
                if (safeLabel.contains("whats")) {
                    phrases.put("open whatsapp")
                    phrases.put("open whats")
                }
                if (safeLabel.contains("youtube")) phrases.put("open yt")
                if (safeLabel.contains("facebook")) phrases.put("open fb")
            }
        }
        return phrases.toString()
    }

    fun saveAlias(context: Context, alias: String, appName: String): Boolean {
        val cleanAlias = normalize(alias)
        val cleanAppName = normalize(appName)
        if (cleanAlias.isBlank() || cleanAppName.isBlank()) return false
        val app = scan(context).firstOrNull { normalize(it.label) == cleanAppName } ?: return false
        context.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(cleanAlias, app.packageName).apply()
        return true
    }

    /** Launch exact matches; offer suggestions for uncertain matches. */
    fun open(context: Context, requestedName: String): String {
        val query = normalize(requestedName)
        if (query.isBlank()) return "Please tell me which app to open."
        val apps = scan(context)
        if (apps.isEmpty()) return "I couldn't find any launchable apps on this phone."

        val scored = apps.map { app -> app to score(context, query, app) }
            .filter { it.second >= SUGGESTION_SCORE }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return "I couldn't find an app matching $requestedName."

        val bestScore = scored.first().second
        val best = scored.filter { it.second == bestScore }
        if (best.size > 1) {
            val names = best.take(3).joinToString(", ") { it.first.label }
            return "I found multiple matching apps: $names. Please say the exact app name."
        }

        val selected = scored.first()
        if (selected.second < EXACT_ENOUGH_SCORE) {
            val suggestions = scored.take(3).joinToString(", ") { it.first.label }
            return "I couldn't find an exact match for $requestedName. Similar apps are: $suggestions. Please say the app name again."
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(selected.first.packageName)
                ?: return "I found ${selected.first.label}, but Android doesn't provide a launch action for it."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening ${selected.first.label}."
        } catch (_: Exception) {
            "I found ${selected.first.label}, but Android couldn't open it."
        }
    }

    private fun score(context: Context, query: String, app: InstalledApp): Int {
        val label = normalize(app.label)
        val packageName = normalize(app.packageName.replace('.', ' '))
        val terms = linkedSetOf(label, packageName)
        label.split(' ').filter { it.length > 1 }.forEach { terms.add(it) }
        if (label.contains("insta")) terms.addAll(listOf("instagram", "insta"))
        if (label.contains("whats")) terms.addAll(listOf("whatsapp", "whats"))
        if (label.contains("youtube")) terms.addAll(listOf("youtube", "yt"))
        if (label.contains("facebook")) terms.addAll(listOf("facebook", "fb"))
        val aliases = context.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE)
            .all.filterValues { it == app.packageName }.keys.filterIsInstance<String>()
        terms.addAll(aliases)
        if (terms.any { it == query }) return 100
        if (terms.any { it.startsWith(query) && query.length >= 3 }) return 90
        if (terms.any { it.contains(query) && query.length >= 4 }) return 82
        return terms.maxOfOrNull { term ->
            val distance = levenshtein(query, term)
            100 - (distance * 100 / max(query.length, term.length).coerceAtLeast(1))
        } ?: 0
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val ALIAS_PREFS = "jarvis_app_aliases"
    private const val MAX_GRAMMAR_APPS = 150
    private const val SUGGESTION_SCORE = 55
    private const val EXACT_ENOUGH_SCORE = 80
}
