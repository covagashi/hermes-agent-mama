package ai.hermes.mama.core.controller

import ai.hermes.mama.contract.BrowserControllerCommandPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Comando tipado del controlador de navegador (ROADMAP §2.6, tarea F2).
 *
 * `browser.controller.command` llega como [BrowserControllerCommandPayload]
 * (`action` + `arguments` sueltos); [from] lo convierte en una acción cerrada
 * para que [WebViewController] no manipule el JsonObject en cada rama.
 *
 * Las acciones que faltan argumentos requeridos producen [Invalid] (resultado
 * `ok:false` con motivo humano en inglés, igual que las herramientas locales);
 * las acciones fuera del protocolo producen [Unsupported].
 */
public sealed class BrowserCommand {
    /** `command_id` del frame del broker; la cancelación lo usa como clave. */
    public abstract val commandId: String

    /** `action` original del wire — útil para logs sin payloads (§8). */
    public abstract val action: String

    /** `controller.noop {}` → `{"success":true}` (sonda de disponibilidad). */
    public data class Noop(
        override val commandId: String,
    ) : BrowserCommand() {
        override val action: String = Actions.NOOP
    }

    /** `browser_navigate {url}` → cargar + settled + snapshot compacto. */
    public data class Navigate(
        override val commandId: String,
        val url: String,
    ) : BrowserCommand() {
        override val action: String = Actions.NAVIGATE
    }

    /** `browser_snapshot {full?}` → snapshot compacto (false) o completo (true). */
    public data class TakeSnapshot(
        override val commandId: String,
        val full: Boolean,
    ) : BrowserCommand() {
        override val action: String = Actions.SNAPSHOT
    }

    /** `browser_click {ref}` — `@eN`/`eN`/`N` los tolera el JS de F1. */
    public data class Click(
        override val commandId: String,
        val ref: String,
    ) : BrowserCommand() {
        override val action: String = Actions.CLICK
    }

    /** `browser_type {ref, text}` — clear+type; el JS rechaza campos no textuales. */
    public data class Type(
        override val commandId: String,
        val ref: String,
        val text: String,
    ) : BrowserCommand() {
        override val action: String = Actions.TYPE
    }

    /** `browser_press {key}` — Enter, Tab, Escape, Arrow*, Home, End, Page*. */
    public data class Press(
        override val commandId: String,
        val key: String,
    ) : BrowserCommand() {
        override val action: String = Actions.PRESS
    }

    /** `browser_scroll {direction}` — up/down/left/right. */
    public data class Scroll(
        override val commandId: String,
        val direction: String,
    ) : BrowserCommand() {
        override val action: String = Actions.SCROLL
    }

    /** `browser_back {}` → `{"success":true,"url":…}` tras el settle. */
    public data class Back(
        override val commandId: String,
    ) : BrowserCommand() {
        override val action: String = Actions.BACK
    }

    /** `browser_screenshot {}` → PNG base64 ≤ 1 200 px (§5/F2). */
    public data class Screenshot(
        override val commandId: String,
    ) : BrowserCommand() {
        override val action: String = Actions.SCREENSHOT
    }

    /** `browser_tabs {}` — el WebView de la app es de una sola pestaña. */
    public data class Tabs(
        override val commandId: String,
    ) : BrowserCommand() {
        override val action: String = Actions.TABS
    }

    /** `browser_tab_activate {id}` — sólo existe la pestaña "1". */
    public data class TabActivate(
        override val commandId: String,
        val tabId: String,
    ) : BrowserCommand() {
        override val action: String = Actions.TAB_ACTIVATE
    }

    /** Acción del protocolo con argumentos inválidos → `ok:false` con motivo. */
    public data class Invalid(
        override val commandId: String,
        override val action: String,
        val reason: String,
    ) : BrowserCommand()

    /** Acción fuera del protocolo §2.6 → `ok:false` con motivo. */
    public data class Unsupported(
        override val commandId: String,
        override val action: String,
    ) : BrowserCommand()

    /** Nombres de `action` del wire (§2.6); también sirven para `capabilities`. */
    public object Actions {
        public const val NOOP: String = "controller.noop"
        public const val NAVIGATE: String = "browser_navigate"
        public const val SNAPSHOT: String = "browser_snapshot"
        public const val CLICK: String = "browser_click"
        public const val TYPE: String = "browser_type"
        public const val SCROLL: String = "browser_scroll"
        public const val BACK: String = "browser_back"
        public const val PRESS: String = "browser_press"
        public const val SCREENSHOT: String = "browser_screenshot"
        public const val TABS: String = "browser_tabs"
        public const val TAB_ACTIVATE: String = "browser_tab_activate"

        /** Capabilities exactas que la app anuncia en `browser.controller.register` (§2.6). */
        public val CAPABILITIES: List<String> =
            listOf(
                NOOP,
                NAVIGATE,
                SNAPSHOT,
                CLICK,
                TYPE,
                SCROLL,
                BACK,
                PRESS,
                SCREENSHOT,
                TABS,
                TAB_ACTIVATE,
            )
    }

    public companion object {
        /** Convierte el payload del wire en comando tipado; nunca lanza. */
        public fun from(payload: BrowserControllerCommandPayload): BrowserCommand {
            val id = payload.commandId
            val action = payload.action
            val args = payload.arguments

            return when (action) {
                Actions.NOOP -> Noop(id)
                Actions.NAVIGATE -> parseNavigate(id, action, args)
                Actions.SNAPSHOT -> TakeSnapshot(id, full = flag(args, "full"))
                Actions.CLICK -> withStringArg(id, action, args, "ref") { Click(id, it) }
                Actions.TYPE -> parseType(id, action, args)
                Actions.PRESS -> withStringArg(id, action, args, "key") { Press(id, it) }
                Actions.SCROLL -> withStringArg(id, action, args, "direction") { Scroll(id, it) }
                Actions.BACK -> Back(id)
                Actions.SCREENSHOT -> Screenshot(id)
                Actions.TABS -> Tabs(id)
                Actions.TAB_ACTIVATE -> withStringArg(id, action, args, "id") { TabActivate(id, it) }
                else -> Unsupported(id, action)
            }
        }

        /**
         * `browser_navigate {url}` — esquema allowlist (frontera §8): el backend
         * no filtra la URL en el camino routed («controller is authoritative»),
         * así que la app sólo navega http(s) y `about:blank`. `javascript:`
         * ejecutaría JS saltándose el masking `[password]` de F1, `data:`
         * cargaría HTML arbitrario (phishing/prompt-injection) y `file:`/
         * `content:` leerían recursos locales — todos → [Invalid].
         */
        private fun parseNavigate(
            id: String,
            action: String,
            args: JsonObject,
        ): BrowserCommand {
            val url = (args["url"] as? JsonPrimitive)?.contentOrNull?.trim()
            return when {
                url.isNullOrEmpty() -> Invalid(id, action, "$action requires a \"url\" argument")
                !isNavigableUrl(url) -> Invalid(id, action, "$action only supports http(s) URLs")
                else -> Navigate(id, url)
            }
        }

        /** `browser_type {ref, text}` — `text` ausente equivale a "" (clear+type del backend). */
        private fun parseType(
            id: String,
            action: String,
            args: JsonObject,
        ): BrowserCommand {
            val ref =
                (args["ref"] as? JsonPrimitive)?.contentOrNull
                    ?: return Invalid(id, action, "$action requires a \"ref\" argument")
            return Type(id, ref, text = (args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }

        /** Acción de un argumento string requerido → comando tipado o [Invalid] con motivo. */
        private fun withStringArg(
            id: String,
            action: String,
            args: JsonObject,
            name: String,
            valid: (String) -> Boolean = { true },
            build: (String) -> BrowserCommand,
        ): BrowserCommand {
            val value = (args[name] as? JsonPrimitive)?.contentOrNull
            return if (value != null && valid(value)) {
                build(value)
            } else {
                Invalid(id, action, "$action requires a \"$name\" argument")
            }
        }

        /** `full?:bool` tolera booleano real y string "true" (argumentos llegan sueltos). */
        private fun flag(
            args: JsonObject,
            name: String,
        ): Boolean {
            val primitive = args[name] as? JsonPrimitive ?: return false
            return primitive.booleanOrNull ?: primitive.contentOrNull?.equals("true", ignoreCase = true) == true
        }

        /**
         * Esquemas navegables por la app (§8): el WebView es la única frontera —
         * `AndroidWebViewDriver.loadUrl` repite esta guarda como defensa en
         * profundidad.
         */
        public fun isNavigableUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) ||
                url.equals("about:blank", ignoreCase = true)
    }
}
