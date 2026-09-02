package com.yugahashimoto.andcode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.core.ProjectLinks
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.feature.assistant.AndCodeVoiceInteractionService
import com.yugahashimoto.andcode.feature.assistant.AssistantStatus
import com.yugahashimoto.andcode.feature.support.GitHubStarPromptDialog
import com.yugahashimoto.andcode.feature.support.openProjectLink
import com.yugahashimoto.andcode.ui.AndCodeApp
import com.yugahashimoto.andcode.ui.ChatDeepLink
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var chatDeepLink by mutableStateOf<ChatDeepLink?>(null)
    private var deepLinkToken = 0L
    private var showInitialStarPrompt by mutableStateOf(false)
    private var assistantActive by mutableStateOf(false)

    private val app: AndCodeApplication
        get() = application as AndCodeApplication

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        assistantActive = AssistantStatus.isActive(this)
        showInitialStarPrompt = app.githubStarCoordinator.shouldShowInitialPrompt()
        app.githubStarCoordinator.refresh()

        setContent {
            val snapshot by app.githubStarCoordinator.snapshot.collectAsState()
            val secondPromptRequested by app.githubStarCoordinator.secondPromptRequested.collectAsState()
            val thankYouRequested by app.githubStarCoordinator.thankYouRequested.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val thankYouMessage = stringResource(R.string.github_star_thanks_snackbar)

            LaunchedEffect(thankYouRequested) {
                if (thankYouRequested) {
                    snackbarHostState.showSnackbar(thankYouMessage)
                    app.githubStarCoordinator.markThankYouShown()
                }
            }

            LaunchedEffect(secondPromptRequested) {
                if (secondPromptRequested) {
                    app.githubStarCoordinator.markSecondPromptPresented()
                }
            }

            Box {
                AndCodeApp(
                    onOpenAssistantSettings = ::openAssistantSettings,
                    assistantActive = assistantActive,
                    chatDeepLink = chatDeepLink,
                    onChatDeepLinkConsumed = { chatDeepLink = null },
                )
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                )
            }

            if (showInitialStarPrompt) {
                GitHubStarPromptDialog(
                    starCount = snapshot.stargazersCount,
                    secondPrompt = false,
                    onStar = {
                        app.githubStarCoordinator.markInitialStarOpened()
                        showInitialStarPrompt = false
                        openProjectLink(this@MainActivity, ProjectLinks.GITHUB_REPOSITORY)
                    },
                    onLater = {
                        app.githubStarCoordinator.markInitialDeferred()
                        showInitialStarPrompt = false
                    },
                )
            }

            if (secondPromptRequested) {
                GitHubStarPromptDialog(
                    starCount = snapshot.stargazersCount,
                    secondPrompt = true,
                    onStar = {
                        app.githubStarCoordinator.markSecondStarOpened()
                        openProjectLink(this@MainActivity, ProjectLinks.GITHUB_REPOSITORY)
                    },
                    onLater = app.githubStarCoordinator::dismissSecondPrompt,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        assistantActive = AssistantStatus.isActive(this)
        app.githubStarCoordinator.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent ?: return
        val sessionId =
            intent.getStringExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID)
                ?.takeIf(String::isNotBlank) ?: return
        deepLinkToken += 1
        chatDeepLink =
            ChatDeepLink(
                sessionId = sessionId,
                runtimeId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID),
                token = deepLinkToken,
            )
        // Consume the extras at once: the activity keeps this intent across configuration changes,
        // and re-delivering it would yank the user back to a chat they have already left.
        intent.removeExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID)
        intent.removeExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID)
    }

    private fun openAssistantSettings() {
        if (assistantActive) {
            AndCodeVoiceInteractionService.show(this, UUID.randomUUID().toString())
            return
        }
        val opened =
            listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            ).any { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            }

        if (!opened) {
            Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
    }
}
