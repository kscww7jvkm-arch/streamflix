package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Utility class to notify ViewModels when the current provider changes.
 *
 * SharedFlow ensures that every active listener receives the provider-change event.
 */
object ProviderChangeNotifier {

    private val _providerChangeFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )

    val providerChangeFlow: SharedFlow<Unit> =
        _providerChangeFlow.asSharedFlow()

    /**
     * Notify all active listeners that the provider has changed.
     */
    fun notifyProviderChanged() {
        _providerChangeFlow.tryEmit(Unit)
    }
}
