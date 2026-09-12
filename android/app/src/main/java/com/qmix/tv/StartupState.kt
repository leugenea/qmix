package com.qmix.tv

data class StartupState(
    val isActivated: Boolean = false,
) {
    fun activate(): StartupState = copy(isActivated = true)
}
