package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ExtractedTask
import com.example.data.model.Priority
import com.example.data.model.Subtask
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class GeminiTaskExtractor {

    companion object {
        private const val TAG = "GeminiTaskExtractor"
        private const val MODEL_NAME = "gemini-1.5-flash"
    }

    private val generativeModel: GenerativeModel by lazy {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }
        GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.2f
                topK = 20
                topP = 0.8f
            }
        )
    }

    suspend fun extractTaskFromInput(rawInput: String): Result<ExtractedTask> = withContext(Dispatchers.IO) {
        if (rawInput.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Input is empty"))
        }

        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }

        // If API key is not configured or is placeholder, use rule-based fallback gracefully
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "GEMINI_API_KEY not configured or placeholder. Using resilient smart local extractor.")
            return@withContext Result.success(parseWithLocalHeuristics(rawInput))
        }

        val now = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentDateStr = dateFormat.format(now.time)
        val currentTimeStr = timeFormat.format(now.time)

        val prompt = """
            You are "Tomorrow", an intelligent assistant that turns unstructured voice notes and brain dumps into structured, actionable tasks.
            
            Current context:
            - Current Date: $currentDateStr
            - Current Time: $currentTimeStr
            
            Analyze the following user input and extract a single primary task or reminder with optional subtasks.
            
            User Input:
            "$rawInput"
            
            Return ONLY a valid JSON object strictly matching this schema, without any conversational preamble or extra text:
            {
              "title": "Clear, concise, actionable task title (5-8 words max)",
              "description": "Additional context or notes extracted from the input, or empty string",
              "category": "Work, Personal, Health, Finance, Errand, Home, or Study",
              "due_date": "YYYY-MM-DD format (infer relative dates like tomorrow, this weekend, next Monday relative to current date) or null",
              "due_time": "HH:mm format (24-hour format, e.g., 09:00, 14:30, or null if no time specified)",
              "priority": "HIGH, MEDIUM, or LOW (based on urgency words like ASAP, crucial, important, today)",
              "subtasks": [
                "Subtask or checklist item 1",
                "Subtask or checklist item 2"
              ]
            }
        """.trimIndent()

        try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: ""
            Log.d(TAG, "Gemini response: $responseText")
            val extracted = parseJsonResponse(responseText, rawInput)
            Result.success(extracted)
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking Gemini SDK, falling back to smart local extractor", e)
            Result.success(parseWithLocalHeuristics(rawInput))
        }
    }

    private fun parseJsonResponse(jsonStr: String, rawInput: String): ExtractedTask {
        // Strip markdown fences if present
        val cleanJson = jsonStr
            .replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^```\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .trim()

        return try {
            val json = JSONObject(cleanJson)
            val title = json.optString("title", "").ifBlank {
                rawInput.lines().firstOrNull()?.take(50) ?: "New Task"
            }
            val description = json.optString("description", "")
            val category = json.optString("category", "General").ifBlank { "General" }
            val priorityStr = json.optString("priority", "MEDIUM")
            val priority = Priority.fromString(priorityStr)

            val dueDateStr = if (json.isNull("due_date")) null else json.optString("due_date", null)
            val dueTimeStr = if (json.isNull("due_time")) null else json.optString("due_time", null)

            val dueTimestamp = computeTimestamp(dueDateStr, dueTimeStr)

            val subtasksList = mutableListOf<Subtask>()
            val subtasksJson = json.optJSONArray("subtasks")
            if (subtasksJson != null) {
                for (i in 0 until subtasksJson.length()) {
                    val subtaskText = subtasksJson.optString(i, "").trim()
                    if (subtaskText.isNotBlank()) {
                        subtasksList.add(Subtask(title = subtaskText))
                    }
                }
            }

            ExtractedTask(
                title = title,
                description = description,
                category = category,
                dueTimestamp = dueTimestamp,
                dueDateString = dueDateStr,
                dueTimeString = dueTimeStr,
                priority = priority,
                subtasks = subtasksList,
                rawInput = rawInput
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing failed for: $cleanJson, using heuristic fallback", e)
            parseWithLocalHeuristics(rawInput)
        }
    }

    private fun computeTimestamp(dateStr: String?, timeStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            val cal = Calendar.getInstance()
            val dateParts = dateStr.split("-")
            if (dateParts.size == 3) {
                val year = dateParts[0].toInt()
                val month = dateParts[1].toInt() - 1
                val day = dateParts[2].toInt()
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
            }

            if (!timeStr.isNullOrBlank()) {
                val timeParts = timeStr.split(":")
                if (timeParts.size >= 2) {
                    val hour = timeParts[0].toInt()
                    val min = timeParts[1].toInt()
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, min)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                }
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resilient offline/local heuristic fallback to handle any parsing or connectivity issues.
     */
    fun parseWithLocalHeuristics(rawInput: String): ExtractedTask {
        val lower = rawInput.lowercase(Locale.getDefault())
        val cal = Calendar.getInstance()
        var hasDate = false
        var dueDateStr: String? = null
        var dueTimeStr: String? = null

        // Detect priority
        val priority = when {
            lower.contains("urgent") || lower.contains("asap") || lower.contains("important") || lower.contains("critical") -> Priority.HIGH
            lower.contains("someday") || lower.contains("low priority") || lower.contains("whenever") || lower.contains("minor") -> Priority.LOW
            else -> Priority.MEDIUM
        }

        // Detect date relative words
        when {
            lower.contains("tomorrow") -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                hasDate = true
            }
            lower.contains("today") || lower.contains("tonight") -> {
                hasDate = true
            }
            lower.contains("next week") -> {
                cal.add(Calendar.DAY_OF_YEAR, 7)
                hasDate = true
            }
            lower.contains("in 2 days") || lower.contains("in two days") -> {
                cal.add(Calendar.DAY_OF_YEAR, 2)
                hasDate = true
            }
            lower.contains("in 3 days") || lower.contains("in three days") -> {
                cal.add(Calendar.DAY_OF_YEAR, 3)
                hasDate = true
            }
        }

        // Time detection
        val timeRegex = Regex("(\\d{1,2})(:(\\d{2}))?\\s*(am|pm)", RegexOption.IGNORE_CASE)
        val timeMatch = timeRegex.find(rawInput)
        if (timeMatch != null) {
            val hourRaw = timeMatch.groupValues[1].toIntOrNull() ?: 9
            val minRaw = timeMatch.groupValues[3].toIntOrNull() ?: 0
            val ampm = timeMatch.groupValues[4].lowercase()
            var hour = hourRaw
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minRaw)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            dueTimeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minRaw)
            hasDate = true
        } else if (lower.contains("morning")) {
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            dueTimeStr = "09:00"
        } else if (lower.contains("afternoon")) {
            cal.set(Calendar.HOUR_OF_DAY, 14)
            cal.set(Calendar.MINUTE, 0)
            dueTimeStr = "14:00"
        } else if (lower.contains("evening") || lower.contains("tonight")) {
            cal.set(Calendar.HOUR_OF_DAY, 19)
            cal.set(Calendar.MINUTE, 0)
            dueTimeStr = "19:00"
        }

        if (hasDate) {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dueDateStr = df.format(cal.time)
        }

        // Category detection
        val category = when {
            lower.contains("doctor") || lower.contains("dentist") || lower.contains("medicine") || lower.contains("workout") || lower.contains("gym") || lower.contains("health") -> "Health"
            lower.contains("meeting") || lower.contains("project") || lower.contains("email") || lower.contains("presentation") || lower.contains("report") || lower.contains("client") || lower.contains("work") -> "Work"
            lower.contains("buy") || lower.contains("groceries") || lower.contains("store") || lower.contains("errand") || lower.contains("pick up") || lower.contains("clean") -> "Errand"
            lower.contains("pay") || lower.contains("invoice") || lower.contains("tax") || lower.contains("bank") || lower.contains("budget") -> "Finance"
            lower.contains("study") || lower.contains("read") || lower.contains("exam") || lower.contains("homework") || lower.contains("class") -> "Study"
            else -> "Personal"
        }

        // Subtask extraction (e.g. numbered list, bullet points, commas after "need to", "remember to", "bring")
        val subtasks = mutableListOf<Subtask>()
        val lines = rawInput.lines()
        if (lines.size > 1) {
            lines.drop(1).forEach { line ->
                val cleaned = line.replace(Regex("^[\\s*\\-•\\d\\.\\)]+"), "").trim()
                if (cleaned.isNotBlank()) {
                    subtasks.add(Subtask(title = cleaned))
                }
            }
        } else {
            // Check for keywords like "bring", "including", "and also"
            val bringIdx = rawInput.indexOf("bring", ignoreCase = true)
            val needIdx = rawInput.indexOf("need to", ignoreCase = true)
            val subpart = when {
                bringIdx >= 0 -> rawInput.substring(bringIdx)
                needIdx >= 0 -> rawInput.substring(needIdx)
                else -> null
            }
            if (subpart != null) {
                val parts = subpart.split(Regex("[,;]|\\band\\b"))
                for (p in parts) {
                    val cleaned = p.replace(Regex("(?i)^(bring|need to|and also|remember to)\\s*"), "").trim()
                    if (cleaned.isNotBlank() && cleaned.length < 60) {
                        subtasks.add(Subtask(title = cleaned.replaceFirstChar { it.uppercase() }))
                    }
                }
            }
        }

        // Format a clean Title
        val firstLine = lines.firstOrNull()?.trim() ?: "Reminder"
        val cleanTitle = firstLine
            .replace(Regex("(?i)^(remember to|i need to|don't forget to|remind me to|please)\\s*"), "")
            .take(60)
            .replaceFirstChar { it.uppercase() }

        return ExtractedTask(
            title = if (cleanTitle.isNotBlank()) cleanTitle else "New Reminder",
            description = if (lines.size > 1) rawInput else "",
            category = category,
            dueTimestamp = if (hasDate) cal.timeInMillis else null,
            dueDateString = dueDateStr,
            dueTimeString = dueTimeStr,
            priority = priority,
            subtasks = subtasks,
            rawInput = rawInput
        )
    }
}
