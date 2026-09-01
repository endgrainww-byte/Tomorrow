package com.example.data.model

data class ExtractedTask(
    val title: String,
    val description: String = "",
    val category: String = "General",
    val dueTimestamp: Long? = null,
    val dueDateString: String? = null,
    val dueTimeString: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val subtasks: List<Subtask> = emptyList(),
    val rawInput: String = ""
)
