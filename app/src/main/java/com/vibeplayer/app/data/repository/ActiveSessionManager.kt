package com.vibeplayer.app.data.repository

import com.vibeplayer.app.model.UserSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the currently active media server session in memory. Set whenever the
 * user opens a service; media screens observe this to perform API requests.
 * Not persisted - sessions are restored from [SecureSessionStore] on demand.
 */
@Singleton
class ActiveSessionManager @Inject constructor() {

    private val _activeSession = MutableStateFlow<UserSession?>(null)
    val activeSession: StateFlow<UserSession?> = _activeSession.asStateFlow()

    fun setActiveSession(session: UserSession?) {
        _activeSession.value = session
    }
}
