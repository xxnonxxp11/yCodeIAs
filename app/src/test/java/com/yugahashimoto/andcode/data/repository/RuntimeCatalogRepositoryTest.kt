package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeConnectionStore
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeCatalogRepositoryTest {
    @Test
    fun `loads selected runtime catalog and workspaces`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository =
                RuntimeCatalogRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                )

            advanceUntilIdle()

            val state = repository.state.value
            assertEquals("mac", state.runtime?.id)
            assertEquals("1.18.3", state.health?.version)
            assertEquals(listOf("s1"), state.sessions.map { it.id })
            assertEquals(listOf("opencode"), state.providers.connected)
            assertEquals(listOf("build"), state.agents.map { it.name })
            assertEquals(listOf("/workspace/app"), state.workspaces.map { it.path })
            assertFalse(state.isRefreshing)
            assertNull(state.error)
        }

    @Test
    fun `switching runtime clears previous catalog and loads the new target`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mac = FakeTarget("mac", version = "1.18.3")
            val linux = FakeTarget("linux", version = "1.18.4")
            val store =
                FakeStore(
                    profiles = listOf(profile("mac"), profile("linux")),
                    selectedRuntimeId = "mac",
                )
            val registry =
                RuntimeRegistry(
                    store = store,
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { if (it.id == "mac") mac else linux },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            registry.select("linux")
            advanceUntilIdle()

            assertEquals("linux", repository.state.value.runtime?.id)
            assertEquals("1.18.4", repository.state.value.health?.version)
        }

    @Test
    fun `connection failure is exposed without stale data`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val failing = FakeTarget("mac", connectError = IllegalStateException("offline"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { failing },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))

            advanceUntilIdle()

            val state = repository.state.value
            assertEquals("offline", state.error)
            assertTrue(state.sessions.isEmpty())
            assertTrue(state.providers.all.isEmpty())
            assertFalse(state.isRefreshing)
        }

    @Test
    fun `refreshing sessions only keeps the existing catalog and updates recent chats`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.refreshSessionsOnly()
            advanceUntilIdle()

            assertEquals(listOf("s1"), repository.state.value.sessions.map { it.id })
            assertEquals(listOf("opencode"), repository.state.value.providers.connected)
        }

    private fun profile(id: String) =
        ConnectionProfile(
            id = id,
            name = id,
            baseUrl = "https://$id.example.test",
        )

    private class FakeStore(
        profiles: List<ConnectionProfile> = listOf(profileStatic("mac")),
        override var selectedRuntimeId: String? = null,
    ) : RuntimeConnectionStore {
        private val values = profiles.toMutableList()

        override fun connections(): List<ConnectionProfile> = values.toList()

        override fun upsertConnection(profile: ConnectionProfile) {
            values.removeAll { it.id == profile.id }
            values += profile
        }

        override fun deleteConnection(id: String) {
            values.removeAll { it.id == id }
        }

        companion object {
            private fun profileStatic(id: String) =
                ConnectionProfile(
                    id = id,
                    name = id,
                    baseUrl = "https://$id.example.test",
                )
        }
    }

    @Test
    fun `switching runtime never shows the previous runtime's providers`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val openCode = FakeTarget("local-android", RuntimeType.LOCAL, providerId = "opencode")
            // A runtime that cannot answer: the worst case, where the old catalogue is most tempting
            // to keep. Claude Code models must never be listed under OpenCode, or the reverse.
            val claude = FakeTarget("claude-code-local", RuntimeType.LOCAL, providersError = IllegalStateException("busy"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "local-android"),
                    localTarget = openCode,
                    additionalTargets = listOf(claude),
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))
            advanceUntilIdle()
            assertEquals(listOf("opencode"), repository.state.value.providers.connected)

            registry.select("claude-code-local")
            advanceUntilIdle()

            assertTrue(repository.state.value.providers.all.isEmpty())
            assertEquals("claude-code-local", repository.state.value.runtime?.id)
        }

    private class FakeTarget(
        override val id: String,
        override val type: RuntimeType = RuntimeType.REMOTE,
        private val version: String = "1.18.3",
        private val connectError: Throwable? = null,
        private val providerId: String = "opencode",
        private val providersError: Throwable? = null,
    ) : RuntimeTarget {
        override val displayName: String = id
        override val kind: BackendKind = if (type == RuntimeType.LOCAL) BackendKind.LOCAL else BackendKind.REMOTE
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

        override suspend fun connect(): Result<OpenCodeHealth> =
            connectError?.let(Result.Companion::failure)
                ?: Result.success(OpenCodeHealth(true, version))

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, version)

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
            listOf(
                OpenCodeSession(id = "s1", title = "Test", directory = "/workspace/app", time = OpenCodeTime(created = 1)),
            )

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog {
            providersError?.let { throw it }
            return ProviderCatalog(
                all =
                    listOf(
                        OpenCodeProvider(
                            id = providerId,
                            name = "OpenCode Zen",
                            models = mapOf("big-pickle" to OpenCodeModel("big-pickle", providerId, "Big Pickle")),
                        ),
                    ),
                default = mapOf(providerId to "big-pickle"),
                connected = listOf(providerId),
            )
        }

        override suspend fun listAgents(): List<OpenCodeAgent> = listOf(OpenCodeAgent("build", mode = "primary"))

        override suspend fun listWorkspaces(): List<WorkspaceRef> =
            listOf(
                WorkspaceRef("/workspace/app", "app", "/workspace/app"),
            )

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
