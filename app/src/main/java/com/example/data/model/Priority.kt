package com.example.data.model

enum class Priority(val label: String, val level: Int) {
    HIGH("High", 3),
    MEDIUM("Medium", 2),
    LOW("Low", 1);

    companion object {
        fun fromString(value: String?): Priority {
            return when (value?.trim()?.uppercase()) {
                "HIGH", "URGENT", "P1" -> HIGH
                "LOW", "MINOR", "P3" -> LOW
                else -> MEDIUM
            }
        }
    }
}
