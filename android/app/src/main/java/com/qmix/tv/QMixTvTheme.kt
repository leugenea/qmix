package com.qmix.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val QMixDarkColorScheme = darkColorScheme()

@Composable
internal fun QMixTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = QMixDarkColorScheme) {
        // TV MaterialTheme does not provide a default content color for bare Text.
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
