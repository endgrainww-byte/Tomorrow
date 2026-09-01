package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.Priority
import com.example.data.model.Subtask
import org.json.JSONArray
import org.json.JSONObject

class Converters {
    @TypeConverter
    fun fromPriority(priority: Priority?): String {
        return priority?.name ?: Priority.MEDIUM.name
    }

    @TypeConverter
    fun toPriority(value: String?): Priority {
        return try {
            if (value.isNullOrBlank()) Priority.MEDIUM else Priority.valueOf(value)
        } catch (e: Exception) {
            Priority.MEDIUM
        }
    }

    @TypeConverter
    fun fromSubtaskList(subtasks: List<Subtask>?): String {
        if (subtasks.isNullOrEmpty()) return "[]"
        val array = JSONArray()
        for (subtask in subtasks) {
            val obj = JSONObject()
            obj.put("id", subtask.id)
            obj.put("title", subtask.title)
            obj.put("isCompleted", subtask.isCompleted)
            array.put(obj)
        }
        return array.toString()
    }

    @TypeConverter
    fun toSubtaskList(jsonString: String?): List<Subtask> {
        if (jsonString.isNullOrBlank()) return emptyList()
        val list = mutableListOf<Subtask>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "")
                val title = obj.optString("title", "")
                val isCompleted = obj.optBoolean("isCompleted", false)
                if (title.isNotBlank()) {
                    list.add(
                        Subtask(
                            id = if (id.isNotBlank()) id else java.util.UUID.randomUUID().toString(),
                            title = title,
                            isCompleted = isCompleted
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Return empty on parsing issue
        }
        return list
    }
}
