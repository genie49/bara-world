package com.bara.api.domain.model

enum class TaskState {
    SUBMITTED,
    WORKING,
    COMPLETED,
    FAILED,
    CANCELED,
    REJECTED;

    val isTerminal: Boolean
        get() = this in TERMINAL_STATES

    companion object {
        private val TERMINAL_STATES = setOf(COMPLETED, FAILED, CANCELED, REJECTED)
    }
}
