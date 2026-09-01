package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiTaskExtractor
import com.example.data.local.AppDatabase
import com.example.data.local.TaskEntity
import com.example.data.model.ExtractedTask
import com.example.data.model.Priority
import com.example.data.model.Subtask
import com.example.data.repository.TaskRepository
import com.example.speech.SpeechRecognitionManager
import com.example.speech.SpeechState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

enum class TaskFilter(val label: String) {
    ALL("All Tasks"),
    UPCOMING("Upcoming"),
    HIGH_PRIORITY("High Priority"),
    COMPLETED("Completed")
}

data class CaptureUiState(
    val inputText: String = "",
    val isAnalyzing: Boolean = false,
    val extractedTask: ExtractedTask? = null,
    val showConfirmationDialog: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

data class TaskListUiState(
    val tasks: List<TaskEntity> = emptyList(),
    val filteredTasks: List<TaskEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: TaskFilter = TaskFilter.ALL,
    val selectedCategory: String? = null,
    val categories: List<String> = emptyList(),
    val editingTask: TaskEntity? = null,
    val recentlyDeletedTask: TaskEntity? = null
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TaskRepository
    private val geminiExtractor = GeminiTaskExtractor()
    val speechManager = SpeechRecognitionManager(application.applicationContext)

    private val _captureState = MutableStateFlow(CaptureUiState())
    val captureState: StateFlow<CaptureUiState> = _captureState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(TaskFilter.ALL)
    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _editingTask = MutableStateFlow<TaskEntity?>(null)
    private val _recentlyDeleted = MutableStateFlow<TaskEntity?>(null)

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val taskListUiState: StateFlow<TaskListUiState>

    init {
        val db = AppDatabase.getInstance(application)
        repository = TaskRepository(db.taskDao())

        taskListUiState = combine(
            repository.allTasks,
            _searchQuery,
            _selectedFilter,
            _selectedCategory,
            _editingTask,
            _recentlyDeleted
        ) { args: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val tasks = args[0] as List<TaskEntity>
            val query = args[1] as String
            val filter = args[2] as TaskFilter
            val category = args[3] as String?
            val editing = args[4] as TaskEntity?
            val recentlyDeleted = args[5] as TaskEntity?

            val distinctCategories = tasks.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

            val filtered = tasks.filter { task ->
                val matchesQuery = query.isBlank() ||
                        task.title.contains(query, ignoreCase = true) ||
                        task.description.contains(query, ignoreCase = true) ||
                        task.category.contains(query, ignoreCase = true)

                val matchesCategory = category == null || task.category.equals(category, ignoreCase = true)

                val matchesFilter = when (filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.UPCOMING -> !task.isCompleted
                    TaskFilter.HIGH_PRIORITY -> task.priority == Priority.HIGH && !task.isCompleted
                    TaskFilter.COMPLETED -> task.isCompleted
                }

                matchesQuery && matchesCategory && matchesFilter
            }

            TaskListUiState(
                tasks = tasks,
                filteredTasks = filtered,
                searchQuery = query,
                selectedFilter = filter,
                selectedCategory = category,
                categories = distinctCategories,
                editingTask = editing,
                recentlyDeletedTask = recentlyDeleted
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TaskListUiState()
        )

        // Listen to speech recognition results
        viewModelScope.launch {
            speechManager.speechState.collect { speechState ->
                when (speechState) {
                    is SpeechState.Success -> {
                        _captureState.update { current ->
                            val combined = if (current.inputText.isBlank()) {
                                speechState.text
                            } else {
                                "${current.inputText} ${speechState.text}"
                            }
                            current.copy(inputText = combined)
                        }
                    }
                    is SpeechState.Error -> {
                        _captureState.update { it.copy(errorMessage = speechState.message) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun onInputTextChanged(newText: String) {
        _captureState.update { it.copy(inputText = newText, errorMessage = null) }
    }

    fun clearInputText() {
        _captureState.update { it.copy(inputText = "") }
        speechManager.resetState()
    }

    fun analyzeAndExtract() {
        val input = _captureState.value.inputText.trim()
        if (input.isBlank()) {
            _captureState.update { it.copy(errorMessage = "Please enter or speak some thoughts first.") }
            return
        }

        _captureState.update { it.copy(isAnalyzing = true, errorMessage = null) }

        viewModelScope.launch {
            val result = geminiExtractor.extractTaskFromInput(input)
            result.onSuccess { extracted ->
                _captureState.update {
                    it.copy(
                        isAnalyzing = false,
                        extractedTask = extracted,
                        showConfirmationDialog = true
                    )
                }
            }.onFailure { error ->
                _captureState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = error.message ?: "Failed to process task."
                    )
                }
            }
        }
    }

    fun dismissConfirmationDialog() {
        _captureState.update { it.copy(showConfirmationDialog = false, extractedTask = null) }
    }

    fun updateExtractedTask(updated: ExtractedTask) {
        _captureState.update { it.copy(extractedTask = updated) }
    }

    fun saveExtractedTask(taskToSave: ExtractedTask) {
        viewModelScope.launch {
            repository.saveExtractedTask(taskToSave)
            _captureState.update {
                it.copy(
                    showConfirmationDialog = false,
                    extractedTask = null,
                    inputText = "",
                    successMessage = "Task saved to Tomorrow!"
                )
            }
            speechManager.resetState()
            _snackbarEvent.emit("Task created successfully!")
        }
    }

    fun toggleTaskCompletion(task: TaskEntity) {
        viewModelScope.launch {
            repository.toggleTaskCompletion(task)
        }
    }

    fun toggleSubtaskCompletion(task: TaskEntity, subtaskId: String) {
        viewModelScope.launch {
            repository.toggleSubtaskCompletion(task.id, subtaskId, task)
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            _recentlyDeleted.value = task
            repository.deleteTask(task)
            _snackbarEvent.emit("Deleted \"${task.title}\"")
        }
    }

    fun undoDelete() {
        val taskToRestore = _recentlyDeleted.value ?: return
        viewModelScope.launch {
            repository.insertTask(taskToRestore)
            _recentlyDeleted.value = null
            _snackbarEvent.emit("Restored \"${taskToRestore.title}\"")
        }
    }

    fun startEditingTask(task: TaskEntity) {
        _editingTask.value = task
    }

    fun dismissEditTask() {
        _editingTask.value = null
    }

    fun saveEditedTask(updatedTask: TaskEntity) {
        viewModelScope.launch {
            repository.updateTask(updatedTask)
            _editingTask.value = null
            _snackbarEvent.emit("Task updated!")
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: TaskFilter) {
        _selectedFilter.value = filter
    }

    fun setCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun clearErrorMessage() {
        _captureState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccessMessage() {
        _captureState.update { it.copy(successMessage = null) }
    }

    fun loadSampleThought(sample: String) {
        _captureState.update { it.copy(inputText = sample) }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
    }
}
