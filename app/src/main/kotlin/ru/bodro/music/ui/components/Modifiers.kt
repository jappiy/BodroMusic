package ru.bodro.music.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/** Нажатие + долгое нажатие (контекстное меню). */
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, role: Role? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick, role = role)
