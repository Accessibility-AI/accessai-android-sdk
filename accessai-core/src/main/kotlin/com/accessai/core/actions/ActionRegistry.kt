package com.accessai.core.actions

import com.accessai.core.auth.DeviceAuthenticator
import com.accessai.core.protocol.ManifestAction
import com.accessai.core.protocol.ManifestParam
import java.util.concurrent.ConcurrentHashMap

/**
 * The allowlist. AccessAI can only ever invoke actions registered here.
 *
 * [deviceAuthenticator] is required before any SENSITIVE action can be registered: no money moves without
 * OS biometric or device-credential authentication.
 */
public class ActionRegistry(public val deviceAuthenticator: DeviceAuthenticator? = null) {
    private val actions = ConcurrentHashMap<String, ActionSpec>()
    private val order = mutableListOf<String>()

    public fun register(spec: ActionSpec) {
        synchronized(order) {
            require(spec.id !in RESERVED) { "'${spec.id}' is reserved for AccessAI's built-in commands" }
            require(!spec.requiresDeviceAuth || deviceAuthenticator != null) {
                "Action '${spec.id}' is SENSITIVE, so it needs a DeviceAuthenticator. Create the registry with one " +
                    "(on Android, AccessAI provides it automatically)."
            }
            if (actions.put(spec.id, spec) == null) order += spec.id
        }
    }

    public fun unregister(id: String) {
        synchronized(order) {
            actions.remove(id)
            order -= id
        }
    }

    public operator fun get(id: String): ActionSpec? = actions[id]

    public fun all(): List<ActionSpec> = synchronized(order) { order.mapNotNull { actions[it] } }

    /** The manifest sent to the interpreter: descriptions and schemas only, never handlers or data. */
    public fun manifest(): List<ManifestAction> = all().map { spec ->
        ManifestAction(
            id = spec.id,
            name = spec.name,
            description = spec.description,
            sensitivity = spec.sensitivity.wireName,
            requiresConfirmation = spec.requiresConfirmation,
            params = spec.params.map { p ->
                ManifestParam(
                    name = p.name,
                    type = p.type.wireName,
                    description = p.description,
                    required = p.required,
                    enumValues = (p.type as? ParamType.OneOf)?.values,
                )
            },
            examples = spec.examples,
        )
    }

    public companion object {
        /** Built-in controls handled by the SDK itself. */
        public val RESERVED: Set<String> = setOf("cancel", "stop", "repeat", "next", "previous", "help", "describe_screen", "confirm")
    }
}
