package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRegistryTest {
    @Test
    fun `loads remote targets and preserves selected runtime`() {
        val store =
            FakeStore(
                profiles = mutableListOf(profile("mac"), profile("linux")),
                selectedId = "linux",
            )
        val local = FakeTarget("local-android", RuntimeType.LOCAL)
        val registry =
            RuntimeRegistry(
                store = store,
                localTarget = local,
                remoteFactory = { FakeTarget(it.id, RuntimeType.REMOTE, it.name) },
            )

        assertEquals(listOf("local-android", "mac", "linux"), registry.targets.value.map { it.id })
        assertEquals("linux", registry.selected.value?.id)
    }

    @Test
    fun `selecting local runtime is persisted`() {
        val store = FakeStore(profiles = mutableListOf(profile("mac")), selectedId = "mac")
        val registry = registry(store)

        registry.select("local-android")

        assertEquals("local-android", store.selectedId)
        assertEquals("local-android", registry.selected.value?.id)
    }

    @Test
    fun `upsert selects first remote runtime when nothing is selected`() {
        val store = FakeStore()
        val registry = registry(store)

        registry.upsertRemote(profile("mac"))

        assertEquals(listOf("local-android", "mac"), registry.targets.value.map { it.id })
        assertEquals("mac", registry.selected.value?.id)
        assertEquals("mac", store.selectedId)
    }

    @Test
    fun `updating selected remote replaces target without losing selection`() {
        val store =
            FakeStore(
                profiles = mutableListOf(profile("mac", "Old name")),
                selectedId = "mac",
            )
        val registry = registry(store)

        registry.upsertRemote(profile("mac", "New name"))

        assertEquals("mac", registry.selected.value?.id)
        assertEquals("New name", registry.selected.value?.displayName)
    }

    @Test
    fun `deleting selected remote falls back to remaining remote before local`() {
        val store =
            FakeStore(
                profiles = mutableListOf(profile("mac"), profile("linux")),
                selectedId = "mac",
            )
        val registry = registry(store)

        registry.deleteRemote("mac")

        assertEquals("linux", registry.selected.value?.id)
        assertEquals("linux", store.selectedId)
    }

    @Test
    fun `deleting last remote clears selection rather than silently choosing unavailable local`() {
        val store =
            FakeStore(
                profiles = mutableListOf(profile("mac")),
                selectedId = "mac",
            )
        val registry = registry(store)

        registry.deleteRemote("mac")

        assertNull(registry.selected.value)
        assertNull(store.selectedId)
    }

    @Test
    fun `saving a connection with select activates it over an already selected local runtime`() {
        val store = FakeStore(selectedId = "local-android")
        val registry = registry(store)

        val activated = registry.upsertRemote(profile("mac"), select = true)

        assertTrue(activated)
        assertEquals("mac", registry.selected.value?.id)
        assertEquals("mac", store.selectedId)
    }

    @Test
    fun `saving a connection without select leaves the running target alone`() {
        val store = FakeStore(selectedId = "local-android")
        val registry = registry(store)

        registry.upsertRemote(profile("mac"), select = false)

        assertEquals("local-android", registry.selected.value?.id)
    }

    @Test
    fun `selecting an unknown target is reported instead of throwing`() {
        val store = FakeStore(profiles = mutableListOf(profile("mac")), selectedId = "mac")
        val registry = registry(store)

        assertFalse(registry.select("deleted-elsewhere"))
        assertEquals("mac", registry.selected.value?.id)
        assertEquals("mac", store.selectedId)
    }

    @Test
    fun `selectIfUnset fills an empty selection but never overrides the user's choice`() {
        val store = FakeStore(profiles = mutableListOf(profile("mac")))
        val registry = registry(store)

        assertTrue(registry.selectIfUnset("local-android"))
        assertEquals("local-android", registry.selected.value?.id)

        registry.select("mac")
        assertFalse(registry.selectIfUnset("local-android"))
        assertEquals("mac", registry.selected.value?.id)
    }

    @Test
    fun `a connection whose target cannot be built is reported instead of failing the registry`() {
        val store =
            FakeStore(
                profiles = mutableListOf(profile("mac"), profile("broken"), profile("linux")),
                selectedId = "linux",
            )
        val registry =
            RuntimeRegistry(
                store = store,
                localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                remoteFactory = { profile ->
                    require(profile.id != "broken") { "Unusable endpoint" }
                    FakeTarget(profile.id, RuntimeType.REMOTE, profile.name)
                },
            )

        assertEquals(listOf("local-android", "mac", "linux"), registry.targets.value.map { it.id })
        assertEquals(setOf("broken"), registry.unusableProfileIds.value)
        assertEquals("linux", registry.selected.value?.id)
    }

    private fun registry(store: FakeStore): RuntimeRegistry =
        RuntimeRegistry(
            store = store,
            localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
            remoteFactory = { FakeTarget(it.id, RuntimeType.REMOTE, it.name) },
        )

    private fun profile(
        id: String,
        name: String = id,
    ): ConnectionProfile =
        ConnectionProfile(
            id = id,
            name = name,
            baseUrl = "https://$id.example.test",
        )

    private class FakeStore(
        val profiles: MutableList<ConnectionProfile> = mutableListOf(),
        override var selectedRuntimeId: String? = null,
        selectedId: String? = null,
    ) : RuntimeConnectionStore {
        init {
            selectedRuntimeId = selectedId
        }

        var selectedId: String?
            get() = selectedRuntimeId
            set(value) {
                selectedRuntimeId = value
            }

        override fun connections(): List<ConnectionProfile> = profiles.toList()

        override fun upsertConnection(profile: ConnectionProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override fun deleteConnection(id: String) {
            profiles.removeAll { it.id == id }
        }
    }

    @Test
    fun `agent settings reach their own runtime, not whichever the chat selected`() {
        val openCode = FakeTarget("local-android", RuntimeType.LOCAL, agent = LocalAgent.OPEN_CODE)
        val claude = FakeTarget("claude-code-local", RuntimeType.LOCAL, agent = LocalAgent.CLAUDE_CODE)
        val registry =
            RuntimeRegistry(
                store = FakeStore(selectedId = "claude-code-local"),
                localTarget = openCode,
                additionalTargets = listOf(claude),
            )

        // The bug this guards: provider settings followed the selected runtime, so with Claude
        // active they went to a runtime with no provider catalogue and the connect button did
        // nothing at all.
        assertEquals("local-android", registry.targetFor(LocalAgent.OPEN_CODE)?.id)
        assertEquals("claude-code-local", registry.targetFor(LocalAgent.CLAUDE_CODE)?.id)
    }

    @Test
    fun `a connected server answers for OpenCode`() {
        val store = FakeStore(profiles = mutableListOf(profile("mac")), selectedId = "mac")
        val registry =
            RuntimeRegistry(
                store = store,
                localTarget = FakeTarget("local-android", RuntimeType.LOCAL, agent = LocalAgent.OPEN_CODE),
                additionalTargets = listOf(FakeTarget("claude-code-local", RuntimeType.LOCAL, agent = LocalAgent.CLAUDE_CODE)),
                remoteFactory = { FakeTarget(it.id, RuntimeType.REMOTE, it.name) },
            )

        // A remote runtime speaks OpenCode's protocol but names no agent; configuring providers
        // while connected to a server has to reach that server, not the phone's own runtime.
        assertEquals("mac", registry.targetFor(LocalAgent.OPEN_CODE)?.id)
    }

    @Test
    fun `reports no runtime for an agent that is not installed`() {
        val registry =
            RuntimeRegistry(
                store = FakeStore(),
                localTarget = FakeTarget("local-android", RuntimeType.LOCAL, agent = LocalAgent.OPEN_CODE),
            )

        assertNull(registry.targetFor(LocalAgent.CLAUDE_CODE))
    }

    private class FakeTarget(
        override val id: String,
        override val type: RuntimeType,
        override val displayName: String = id,
        override val agent: LocalAgent? = null,
    ) : RuntimeTarget {
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
        override val kind: BackendKind = if (type == RuntimeType.LOCAL) BackendKind.LOCAL else BackendKind.REMOTE

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "test"))

        override fun disconnect() = Unit

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

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
