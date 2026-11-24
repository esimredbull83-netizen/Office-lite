package com.example.drive

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String) : DriveAuthOutcome
    data class NeedsUserConsent(val intentSenderRequest: IntentSenderRequest) : DriveAuthOutcome
    data class Failed(val message: String) : DriveAuthOutcome
}

class DriveAuthManager {
    // Keep short-lived OAuth access token in memory only (never written to disk or logs)
    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    private val _userInfo = MutableStateFlow<DriveUserInfo?>(null)
    val userInfo: StateFlow<DriveUserInfo?> = _userInfo.asStateFlow()

    @Volatile
    private var cachedFolderId: String? = null

    val isConnected: Boolean
        get() = !_accessToken.value.isNullOrBlank()

    suspend fun requestAuthorization(context: Context): DriveAuthOutcome {
        val existing = _accessToken.value
        if (!existing.isNullOrBlank()) {
            return DriveAuthOutcome.Authorized(existing)
        }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GoogleDriveClient.DRIVE_FILE_SCOPE)))
            .build()

        return suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context)
                .authorize(request)
                .addOnSuccessListener { result ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val pendingIntent = result.pendingIntent
                    val token = result.accessToken
                    when {
                        result.hasResolution() && pendingIntent != null -> {
                            val senderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            continuation.resume(DriveAuthOutcome.NeedsUserConsent(senderRequest))
                        }
                        !token.isNullOrBlank() -> {
                            _accessToken.value = token
                            continuation.resume(DriveAuthOutcome.Authorized(token))
                        }
                        else -> {
                            continuation.resume(
                                DriveAuthOutcome.Failed("Google Drive authorization returned no access token.")
                            )
                        }
                    }
                }
                .addOnFailureListener { err ->
                    if (!continuation.isActive) return@addOnFailureListener
                    continuation.resume(
                        DriveAuthOutcome.Failed(
                            err.localizedMessage ?: "Google Play Services authorization unavailable."
                        )
                    )
                }
        }
    }

    fun handleConsentResult(context: Context, data: Intent?): DriveAuthOutcome {
        return try {
            val result: AuthorizationResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                _accessToken.value = token
                DriveAuthOutcome.Authorized(token)
            } else {
                DriveAuthOutcome.Failed("Google Drive permission was not granted.")
            }
        } catch (e: ApiException) {
            DriveAuthOutcome.Failed("Authorization cancelled or failed (${e.statusCode}).")
        } catch (e: Exception) {
            DriveAuthOutcome.Failed(e.localizedMessage ?: "Authorization failed.")
        }
    }

    fun setUserInfo(info: DriveUserInfo?) {
        _userInfo.value = info
    }

    fun getCachedFolderId(): String? = cachedFolderId

    fun setCachedFolderId(id: String) {
        cachedFolderId = id
    }

    fun invalidateTokenOnly() {
        _accessToken.value = null
        cachedFolderId = null
    }

    fun disconnect() {
        _accessToken.value = null
        _userInfo.value = null
        cachedFolderId = null
    }

    companion object {
        @Volatile
        private var instance: DriveAuthManager? = null

        fun getInstance(): DriveAuthManager {
            return instance ?: synchronized(this) {
                instance ?: DriveAuthManager().also { instance = it }
            }
        }
    }
}
