package com.charlesh.captionburn.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Onboarding : Route
    @Serializable data object Home : Route
    @Serializable data class Editor(val projectId: String) : Route
    @Serializable data class Export(val projectId: String) : Route
    @Serializable data object Settings : Route
    @Serializable data object MoreApps : Route
}
