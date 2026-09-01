package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Priority
import com.example.data.model.Subtask

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val category: String = "General",
    val dueTimestamp: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val subtasks: List<Subtask> = emptyList(),
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val originalPrompt: String = ""
)
