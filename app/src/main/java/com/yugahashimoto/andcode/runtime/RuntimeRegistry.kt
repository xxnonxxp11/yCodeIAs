package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.remote.RemoteRuntimeTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RuntimeRegistry(
    private val store: RuntimeConnectionStore,
    private val localTarget: RuntimeTarget,
    private val additionalTargets: List<RuntimeTarget> = emptyList(),
    private val remoteFactory: (ConnectionProfile) -> RuntimeTarget = { profile ->
        RemoteRuntimeTarget(profile)
    },
) {
    /**
     * Ids of stored connections whose runtime target could not be built (unusable endpoint).
     *
     * Declared before the target list because buildTargets() writes to it while constructing that
     * list.
     */
    private val mutableUnusableProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val unusableProfileIds: StateFlow<Set<String>> = mutableUnusableProfileIds.asStateFlow()

    private val mutableTargets = MutableStateFlow(buildTargets())
    val targets: StateFlow<List<RuntimeTarget>> = mutableTargets.asStateFlow()

    private val mutableSelected = MutableStateFlow(resolveSelected(mutableTargets.value))
    val selected: StateFlow<RuntimeTarget?> = mutableSelected.asStateFlow()

    fun remoteProfiles(): List<ConnectionProfile> = store.connections()

    fun target(id: String): RuntimeTarget? = mutableTargets.value.firstOrNull { it.id == id }

    /**
     * Runtime that answers for [agent].
     *
     * Settings screens belong to one agent — MCP servers, providers and commands are configured per
     * agent, not per chat — so they must not read whatever the chat happens to have selected. A
     * remote runtime speaks OpenCode's protocol, hence the preference for the selected one when it
     * is not another agent's: a user connected to a server expects to configure that server.
     */
    fun targetFor(agent: LocalAgent): RuntimeTarget? {
        val selected = mutableSelected.value
        if (selected != null && (selected.agent == agent || (selected.agent == null && agent == LocalAgent.OPEN_CODE))) {
            return selected
        }
        return mutableTargets.value.firstOrNull { it.agent == agent }
            ?: mutableTargets.value.firstOrNull { it.agent == null && agent == LocalAgent.OPEN_CODE }
    }

    /**
     * Selects [id] as the active runtime.
     *
     * Selection is driven from UI callbacks, background collectors and persisted state that can all
     * race with a connection being deleted, so an unknown id is reported rather than thrown: taking
     * the process down because a stale id survived somewhere is never the right answer.
     *
     * @return true when [id] was applied.
     */
    fun select(id: String?): Boolean {
        val target = id?.let { targetId -> mutableTargets.value.firstOrNull { it.id == targetId } }
        if (id != null && target == null) return false
        store.selectedRuntimeId = id
        mutableSelected.value = target
        return true
    }

    /**
     * Selects [id] only when nothing is selected yet, so background collectors (the local runtime
     * becoming ready, auto-start) can establish a default without ever overriding the runtime the
     * user picked themselves.
     *
     * @return true when this call established the selection.
     */
    fun selectIfUnset(id: String): Boolean {
        if (mutableSelected.value != null) return false
        return select(id)
    }

    /**
     * Stores [profile] and rebuilds the target list.
     *
     * @param select when true the saved connection also becomes the active runtime. The remote
     *   connection screen passes true: the user pressed "connect", so leaving the previously
     *   selected runtime (typically the Android-local one) in place would silently ignore them.
     */
    fun upsertRemote(
        profile: ConnectionProfile,
        select: Boolean = false,
    ): Boolean {
        val selectedBefore = store.selectedRuntimeId
        store.upsertConnection(profile)
        rebuildTargets()

        return when {
            select -> select(profile.id)
            selectedBefore == profile.id -> select(profile.id)
            selectedBefore == null -> select(profile.id)
            else -> {
                mutableSelected.value = resolveSelected(mutableTargets.value)
                false
            }
        }
    }

    fun deleteRemote(id: String) {
        val wasSelected = store.selectedRuntimeId == id
        store.deleteConnection(id)
        rebuildTargets()

        if (wasSelected) {
            val fallback = store.connections().firstOrNull()?.id
            select(fallback)
        } else {
            mutableSelected.value = resolveSelected(mutableTargets.value)
        }
    }

    fun refresh() {
        rebuildTargets()
        mutableSelected.value = resolveSelected(mutableTargets.value)
    }

    private fun buildTargets(): List<RuntimeTarget> {
        val unusable = mutableSetOf<String>()
        val targets =
            buildList {
                add(localTarget)
                addAll(additionalTargets)
                store.connections().forEach { profile ->
                    // A stored endpoint can stop being usable across app versions (tightened URL
                    // rules) or arrive broken from a QR payload. Building the target must not throw
                    // here: this runs on the main thread from app start, refresh and save.
                    val target = runCatching { remoteFactory(profile) }.getOrNull()
                    if (target == null) unusable += profile.id else add(target)
                }
            }
        mutableUnusableProfileIds.value = unusable
        return targets
    }

    private fun rebuildTargets() {
        mutableTargets.value = buildTargets()
    }

    // A stored id that resolves to nothing (connection deleted elsewhere, or an endpoint that no
    // longer builds) yields no selection rather than a substitute: the local runtime may not even
    // be installed. The stored id is left untouched so the selection returns if the target does.
    private fun resolveSelected(targets: List<RuntimeTarget>): RuntimeTarget? =
        store.selectedRuntimeId?.let { selectedId -> targets.firstOrNull { it.id == selectedId } }
}
