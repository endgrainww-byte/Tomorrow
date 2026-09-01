package com.example.data.repository

import com.example.data.local.TaskDao
import com.example.data.local.TaskEntity
import com.example.data.model.ExtractedTask
import com.example.data.model.Subtask
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    val allTasks: Flow<List<TaskEntity>> = taskDao.getAllTasks()
    val pendingTasks: Flow<List<TaskEntity>> = taskDao.getPendingTasks()
    val completedTasks: Flow<List<TaskEntity>> = taskDao.getCompletedTasks()

    fun searchTasks(query: String): Flow<List<TaskEntity>> = taskDao.searchTasks(query)

    fun getTaskById(id: Long): Flow<TaskEntity?> = taskDao.getTaskById(id)

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun saveExtractedTask(extracted: ExtractedTask): Long {
        val entity = TaskEntity(
            title = extracted.title.ifBlank { "New Reminder" },
            description = extracted.description,
            category = extracted.category.ifBlank { "General" },
            dueTimestamp = extracted.dueTimestamp,
            priority = extracted.priority,
            subtasks = extracted.subtasks,
            isCompleted = false,
            originalPrompt = extracted.rawInput
        )
        return taskDao.insertTask(entity)
    }

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun toggleTaskCompletion(task: TaskEntity) {
        val updated = task.copy(isCompleted = !task.isCompleted)
        taskDao.updateTask(updated)
    }

    suspend fun toggleSubtaskCompletion(taskId: Long, subtaskId: String, currentTask: TaskEntity) {
        val updatedSubtasks = currentTask.subtasks.map {
            if (it.id == subtaskId) it.copy(isCompleted = !it.isCompleted) else it
        }
        val allDone = updatedSubtasks.isNotEmpty() && updatedSubtasks.all { it.isCompleted }
        taskDao.updateTask(currentTask.copy(subtasks = updatedSubtasks, isCompleted = if (allDone) true else currentTask.isCompleted))
    }

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)

    suspend fun deleteTaskById(id: Long) = taskDao.deleteTaskById(id)
}
