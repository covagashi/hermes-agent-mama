#!/usr/bin/env python3
"""Genera los DTOs Kotlin de la app "Hermes para mamá" desde el contrato OpenRPC.

Lee ``apps/shared/src/gateway-contract.openrpc.json`` (fuente de verdad del wire
JSON-RPC de ``hermes serve``) y escribe
``apps/android/core-contract/src/main/kotlin/ai/hermes/mama/contract/Generated.kt``.

Tarea A3 del roadmap Android (``apps/android/ROADMAP.md`` §5). Python 3.11+, sólo
stdlib — corre igual en CI que en local.

Reglas de mapeo (ROADMAP §A3):
  * ``anyOf: [T, null]``            -> ``T? = null``
  * ``additionalProperties: true``  -> propiedad ``JsonObject`` (y en clases
    abiertas, un campo ``extraKeys`` documentado)
  * ``enum``                        -> ``@Serializable enum class``
  * ``$ref`` internos               -> se resuelven contra ``components.schemas``
  * ``snake_case`` en el wire       -> camelCase + ``@SerialName``

Uso:
    python3 scripts/gen_android_contract.py            # regenera Generated.kt
    python3 scripts/gen_android_contract.py --check    # exit 0 si está al día
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONTRACT = REPO_ROOT / "apps/shared/src/gateway-contract.openrpc.json"
DEFAULT_OUTPUT = (
    REPO_ROOT
    / "apps/android/core-contract/src/main/kotlin/ai/hermes/mama/contract/Generated.kt"
)

# ---------------------------------------------------------------------------
# Qué genera la app (ROADMAP §2.3–2.6). Lista explícita y deliberada: la app es
# un cliente minimalista, no le sirven los 215 métodos del contrato.
# ---------------------------------------------------------------------------

# Métodos JSON-RPC cliente→servidor que la app llama (ROADMAP §2.3).
# ``gateway.ping`` es el heartbeat a nivel WS: ``tui_gateway/ws.py`` lo responde
# antes del dispatch (no es el método ``ping`` del contrato, que es stdio).
RPC_METHODS = [
    "gateway.ping",  # heartbeat WS (§2.2); respondido en ws.py, no en el contrato
    "gateway.capabilities",
    "session.list",
    "session.create",
    "session.resume",
    "session.history",
    "session.title",
    "session.delete",
    "session.interrupt",
    "session.events.since",
    "prompt.submit",
    "image.attach_bytes",
    "file.attach",
    "approval.pending",
    "approval.respond",
    "request.answer",
    "browser.controller.register",
    "browser.controller.result",
    "browser.controller.heartbeat",
    "browser.controller.detach",
]

# Métodos wire que NO aparecen como ``methods`` del contrato (heartbeat WS).
_WIRE_ONLY_METHODS = {"gateway.ping"}

# Schemas de components.schemas que cubren §2.3–2.6: params/results de los
# métodos de la app, payloads de los eventos §2.4, peticiones servidor→cliente
# §2.5 y el protocolo browser.controller §2.6 (más los $ref transitivos).
SCHEMAS = [
    # --- §2.3: ping / capabilities ---
    "PingParams",
    "PingResult",
    "OkResult",
    "GatewayCapabilitiesResult",
    # --- §2.3: sessions ---
    "SessionListParams",
    "SessionListResult",
    "SessionListRow",
    "SessionCreateParams",
    "SessionCreateResult",
    "SessionResumeParams",
    "SessionResumeResult",
    "SessionHistoryParams",
    "SessionHistoryResult",
    "SessionTitleParams",
    "SessionTitleResult",
    "SessionDeleteParams",
    "SessionDeleteResult",
    "SessionInterruptParams",
    "SessionInterruptResult",
    "SessionEventsSinceParams",
    "SessionEventsSinceResult",
    # --- §2.3: prompt / adjuntos ---
    "PromptSubmitParams",
    "PromptSubmitResult",
    "ImageAttachBytesParams",
    "AttachedImageResult",
    "FileAttachParams",
    "FileAttachResult",
    # --- §2.3 + §2.5: approvals / clarify ---
    "ApprovalPendingParams",
    "ApprovalPendingResult",
    "ApprovalRespondParams",
    "ApprovalRespondResult",
    "ApprovalRequestParams",
    "ApprovalResult",
    "ApprovalChoice",
    "PendingApproval",
    "ClarifyRequestParams",
    "ClarifyQuestion",
    "ClarifyResult",
    "ClarifyLockStatus",
    "RequestAnswerParams",
    "RequestAnswerResult",
    # --- §2.5: peticiones servidor→cliente no soportadas (params para log) ---
    "SecretRequestParams",
    "EmptyRequestParams",
    "VaultCodeRequestParams",
    "VaultSaveLoginRequestParams",
    "VaultUnlockRequestParams",
    "McpSetupRequestParams",
    "PreviewActRequestParams",
    "ReadRangeRequestParams",
    "TourRequestParams",
    "TourStep",
    "ValueResult",
    # --- §2.6: browser.controller ---
    "BrowserControllerRegisterParams",
    "BrowserControllerRegisterResult",
    "BrowserControllerResultParams",
    "BrowserControllerResultResult",
    "BrowserControllerParams",
    "BrowserControllerDetachResult",
    "BrowserControllerCommandPayload",
    "BrowserControllerCancelPayload",
    "BrowserProgressPayload",
    "ControllerScope",
    # --- §2.4: payloads de eventos ---
    "GatewayReadyPayload",
    "SkinPayload",
    "StreamDeltaPayload",
    "MessageCompletePayload",
    "MessageInterimPayload",
    "ToolStartPayload",
    "ToolCompletePayload",
    "StatusUpdatePayload",
    "SessionTitlePayload",
    "ChangeSignalPayload",
    "SessionLiveInfo",
    "ErrorPayload",
    "NoticePayload",
    "RequestCancelPayload",
    "VoiceTranscriptPayload",
    # --- tipos de apoyo referenciados por los anteriores ---
    "TranscriptMessage",
    "SeedMessage",
    "OpenRequestEntry",
    "InflightTurn",
    "QueuedPrompt",
    "Usage",
    "TodoState",
    "AutoContinue",
    "ProjectRef",
    "McpServerStatus",
    "InterruptStatus",
    "PromptSubmitStatus",
    "TurnStatus",
    "BillingBlock",
    "ErrorSurface",
]

_KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
}

_LINE_LIMIT = 118  # .editorconfig: max_line_length = 120; margen para " * ".


class GenerationError(Exception):
    """El contrato tiene una forma que el generador no sabe mapear."""


# ---------------------------------------------------------------------------
# Utilidades de nombres
# ---------------------------------------------------------------------------

def _camel_case(wire_name: str) -> str:
    """``session_id`` -> ``sessionId``. Respeta siglas y dígitos."""
    parts = re.split(r"[_\-\s]+", wire_name)
    head = parts[0][:1].lower() + parts[0][1:]
    tail = "".join(p[:1].upper() + p[1:] for p in parts[1:] if p)
    name = head + tail
    if name in _KOTLIN_KEYWORDS:
        name += "_"
    if not re.match(r"^[A-Za-z_]", name):
        name = "_" + name
    return name


def _const_name(wire_name: str) -> str:
    """``browser.controller.command`` -> ``BROWSER_CONTROLLER_COMMAND``."""
    name = re.sub(r"[^0-9A-Za-z]+", "_", wire_name).strip("_").upper()
    if name[:1].isdigit():
        name = "_" + name
    return name


def _enum_entry(value: str) -> str:
    """``not_interrupted`` -> ``NOT_INTERRUPTED``."""
    return _const_name(value)


def _kotlin_string(value: str) -> str:
    """Literal de string Kotlin (escapa ``\\``, ``"``, ``$`` y controles)."""
    out = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
    return '"' + out + '"'


def _kdoc(text: str, indent: str = "") -> list[str]:
    """KDoc de varias líneas con wrap a ``_LINE_LIMIT`` columnas."""
    text = re.sub(r"\s+", " ", (text or "").strip()).replace("*/", "* /")
    if not text:
        return []
    words = text.split()
    lines: list[str] = []
    cur = ""
    for word in words:
        candidate = f"{cur} {word}".strip()
        if len(candidate) > _LINE_LIMIT - len(indent) - 3 and cur:
            lines.append(cur)
            cur = word
        else:
            cur = candidate
    if cur:
        lines.append(cur)
    if len(lines) == 1:
        return [f"{indent}/** {lines[0]} */"]
    return [f"{indent}/**"] + [f"{indent} * {line}" for line in lines] + [f"{indent} */"]


# ---------------------------------------------------------------------------
# Mapeo schema JSON -> tipo Kotlin
# ---------------------------------------------------------------------------

def _ref_name(ref: str) -> str:
    if not ref.startswith("#/components/schemas/"):
        raise GenerationError(f"$ref externo no soportado: {ref}")
    return ref.rsplit("/", 1)[-1]


class TypeMapper:
    """Convierte subschemas del contrato a tipos Kotlin."""

    def __init__(self, schemas: dict, wanted: set[str]):
        self.schemas = schemas
        self.wanted = wanted

    def kotlin_type(self, schema: dict) -> tuple[str, bool]:
        """Devuelve ``(tipo, nullable)`` para un subschema de propiedad."""
        if not isinstance(schema, dict):
            raise GenerationError(f"subschema no-objeto: {schema!r}")
        if "$ref" in schema:
            name = _ref_name(schema["$ref"])
            if name not in self.wanted:
                raise GenerationError(
                    f"$ref a {name} fuera de SCHEMAS — añádelo a la lista del script"
                )
            return name, False
        if "anyOf" in schema or "oneOf" in schema:
            variants = schema.get("anyOf") or schema["oneOf"]
            return self._union_type(variants)
        if "enum" in schema:
            # Enum inline (sin $ref): degradamos a String — el contrato lista los
            # valores en el comentario que emite el generador.
            return "String", False
        jtype = schema.get("type")
        if jtype == "string":
            return "String", False
        if jtype == "integer":
            return "Long", False
        if jtype == "number":
            return "Double", False
        if jtype == "boolean":
            return "Boolean", False
        if jtype == "array":
            return f"List<{self._items_type(schema.get('items'))}>", False
        if jtype == "object" or "properties" in schema or "additionalProperties" in schema:
            return self._object_type(schema), False
        if jtype == "null":
            return "JsonElement", True
        if jtype is None:
            # Schema vacío o sólo con ``default``/``title``: cualquier JSON.
            return "JsonElement", False
        raise GenerationError(f"tipo JSON no soportado: {schema!r}")

    def _union_type(self, variants: list) -> tuple[str, bool]:
        """``anyOf [T, null]`` -> ``(T, True)``; uniones más ricas -> JsonElement."""
        non_null = [v for v in variants if not (isinstance(v, dict) and v.get("type") == "null")]
        nullable = len(non_null) != len(variants)
        if len(non_null) == 1:
            ktype, inner_nullable = self.kotlin_type(non_null[0])
            return ktype, nullable or inner_nullable
        # Variante vacía ``{}`` = cualquier JSON; varios tipos reales = union
        # abierta. Ambas se representan como JsonElement.
        return "JsonElement", nullable

    def _items_type(self, items) -> str:
        if not isinstance(items, dict) or not items:
            return "JsonElement"
        ktype, nullable = self.kotlin_type(items)
        return f"{ktype}?" if nullable else ktype

    def _object_type(self, schema: dict) -> str:
        """``type: object`` libre -> JsonObject; con ``additionalProperties``
        tipado -> ``Map<String, V>``."""
        extra = schema.get("additionalProperties")
        if isinstance(extra, dict) and extra:
            ktype, nullable = self.kotlin_type(extra)
            return f"Map<String, {ktype}{'?' if nullable else ''}>"
        return "JsonObject"


def _default_literal(value, ktype: str, enums: set[str]) -> str:
    """Literal Kotlin para un ``default`` JSON del contrato."""
    if value is None:
        return "null"
    if ktype == "JsonElement":
        return _json_element_literal(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        if ktype == "Long":
            return f"{value}L"
        if ktype == "Double":
            return f"{value}.0"
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, str):
        if ktype in enums:
            return f"{ktype}.{_enum_entry(value)}"
        return _kotlin_string(value)
    return _json_element_literal(value)


def _json_element_literal(value) -> str:
    """Literal JsonElement para defaults JSON (escalares o compuestos)."""
    if isinstance(value, dict):
        inner = ", ".join(
            f"{_kotlin_string(str(k))} to {_json_element_literal(v)}" for k, v in value.items()
        )
        return f"JsonObject(mapOf({inner}))"
    if isinstance(value, list):
        inner = ", ".join(_json_element_literal(v) for v in value)
        return f"JsonArray(listOf({inner}))"
    if isinstance(value, bool):
        return f"JsonPrimitive({'true' if value else 'false'})"
    if isinstance(value, (int, float)):
        return f"JsonPrimitive({value})"
    return f"JsonPrimitive({_kotlin_string(str(value))})"


# ---------------------------------------------------------------------------
# Emisión Kotlin
# ---------------------------------------------------------------------------

def _emit_const_object(name: str, doc: str, entries: list[tuple[str, str, str | None]]) -> list[str]:
    """``object X { const val ... }`` — entries: (CONST, valor, comentario?)."""
    lines = _kdoc(doc)
    lines.append(f"object {name} {{")
    for const, value, comment in entries:
        if comment:
            lines.append(f"    /** {comment} */")
        lines.append(f"    const val {const} = {_kotlin_string(value)}")
    lines.append("}")
    return lines


def _emit_enum(name: str, schema: dict) -> list[str]:
    lines = _kdoc(schema.get("description") or "")
    lines.append("@Serializable")
    lines.append(f"enum class {name} {{")
    for i, value in enumerate(schema["enum"]):
        if i:
            # ktlint: separación obligatoria entre declaraciones anotadas.
            lines.append("")
        entry = _enum_entry(value)
        lines.append(f"    @SerialName({_kotlin_string(value)})")
        lines.append(f"    {entry},")
    lines.append("}")
    return lines


def _emit_data_class(name: str, schema: dict, mapper: TypeMapper, enums: set[str]) -> list[str]:
    props: dict = schema.get("properties") or {}
    required = set(schema.get("required") or [])
    open_model = schema.get("additionalProperties") is True

    if not props and not open_model:
        # Objeto cerrado sin propiedades: singleton que serializa ``{}``.
        lines = _kdoc(schema.get("description") or f"``{name}``: objeto vacío del contrato.")
        lines += ["@Serializable", f"object {name}"]
        return lines

    lines = _kdoc(schema.get("description") or "")
    lines.append("@Serializable")
    header = f"data class {name}("
    body: list[str] = []
    for wire_name, prop in props.items():
        ktype, nullable = mapper.kotlin_type(prop)
        kname = _camel_case(wire_name)
        is_required = wire_name in required
        suffix = "?" if nullable else ""
        if is_required:
            default = ""
        elif "default" in prop and prop["default"] is not None:
            default = f" = {_default_literal(prop['default'], ktype, enums)}"
        else:
            # Opcional sin default explícito: nullable con ``= null``.
            suffix = "?"
            default = " = null"
        decl = f"val {kname}: {ktype}{suffix}{default}"
        if kname != wire_name:
            body.append(f"    @SerialName({_kotlin_string(wire_name)})")
        body.append(f"    {decl},")
    if open_model:
        body += [
            "    /**",
            "     * Claves de wire no modeladas (el schema es abierto:",
            "     * ``additionalProperties: true``). ``@Transient``: kotlinx no las",
            "     * captura al decodificar; queda como receptáculo documentado.",
            "     */",
            "    @Transient",
            "    val extraKeys: JsonObject? = null,",
        ]
    # ktlint-official: la lista de propiedades del constructor siempre multilínea.
    lines.append(header)
    lines += body
    lines.append(")")
    return lines


def generate(contract: dict, source_hash: str) -> str:
    schemas = contract["components"]["schemas"]
    wanted = set(SCHEMAS)
    missing = [name for name in SCHEMAS if name not in schemas]
    if missing:
        raise GenerationError(f"schemas de SCHEMAS ausentes en el contrato: {missing}")
    mapper = TypeMapper(schemas, wanted)
    enums = {name for name in SCHEMAS if "enum" in schemas[name]}

    contract_methods = {m["name"] for m in contract.get("methods", [])}
    unknown = [
        m for m in RPC_METHODS
        if m not in contract_methods and m not in _WIRE_ONLY_METHODS
    ]
    if unknown:
        raise GenerationError(f"métodos de RPC_METHODS ausentes en el contrato: {unknown}")

    notifications = [n["name"] for n in contract.get("x-notifications", [])]
    server_requests = [r["name"] for r in contract.get("x-server-requests", [])]

    out: list[str] = []

    rpc_entries = [
        (
            _const_name(name),
            name,
            "Heartbeat a nivel WS (§2.2): lo responde ws.py antes del dispatch."
            if name in _WIRE_ONLY_METHODS
            else None,
        )
        for name in RPC_METHODS
    ]
    out += _emit_const_object(
        "RpcMethods",
        "Métodos JSON-RPC cliente→servidor que usa la app (ROADMAP §2.3).",
        rpc_entries,
    )
    out.append("")
    out += _emit_const_object(
        "EventTypes",
        "Tipos de evento servidor→cliente ``event.params.type`` (ROADMAP §2.4; "
        "los no listados se toleran sin fallar).",
        [(_const_name(name), name, None) for name in sorted(notifications)],
    )
    out.append("")
    out += _emit_const_object(
        "ServerRequests",
        "Peticiones servidor→cliente que la app puede recibir (ROADMAP §2.5); "
        "sólo ``approval`` y ``clarify`` se atienden, el resto → -32601.",
        [(_const_name(name), name, None) for name in sorted(server_requests)],
    )
    out.append("")

    # Mapa nombre-de-schema → serializer: dispatch de payloads en el cliente y
    # en GeneratedRoundTripTest.
    out += _kdoc(
        "Serializers por nombre de schema del contrato (B4 los usa para "
        "decodificar payloads tipados; el round-trip test los recorre todos)."
    )
    out.append("object ContractSerializers {")
    out.append("    val bySchema: Map<String, KSerializer<*>> =")
    out.append("        mapOf(")
    for name in SCHEMAS:
        out.append(f"            {_kotlin_string(name)} to serializer<{name}>(),")
    out.append("        )")
    out.append("}")
    out.append("")

    for name in SCHEMAS:
        schema = schemas[name]
        if "enum" in schema:
            out += _emit_enum(name, schema)
        else:
            out += _emit_data_class(name, schema, mapper, enums)
        out.append("")

    body = "\n".join(out).rstrip("\n") + "\n"

    # Imports sólo de los símbolos realmente emitidos (ktlint: no-unused-imports).
    imports = [
        "kotlinx.serialization.KSerializer",
        "kotlinx.serialization.SerialName",
        "kotlinx.serialization.Serializable",
    ]
    for token, imp in [
        ("@Transient", "kotlinx.serialization.Transient"),
        ("JsonArray(", "kotlinx.serialization.json.JsonArray"),
        ("JsonElement", "kotlinx.serialization.json.JsonElement"),
        ("JsonObject", "kotlinx.serialization.json.JsonObject"),
        ("JsonPrimitive(", "kotlinx.serialization.json.JsonPrimitive"),
        ("serializer<", "kotlinx.serialization.serializer"),
    ]:
        if token in body:
            imports.append(imp)
    imports = sorted(set(imports))

    header = "\n".join(
        [
            "// GENERATED — do not edit",
            "// Source: apps/shared/src/gateway-contract.openrpc.json",
            f"// Contract SHA-256: {source_hash}",
            "// Regenerate: python3 scripts/gen_android_contract.py",
            "",
            '@file:Suppress("LargeClass")',
            "",
            "package ai.hermes.mama.contract",
            "",
            *[f"import {imp}" for imp in imports],
            "",
            "",
        ]
    )
    return header + body


def load_contract(path: Path) -> tuple[dict, str]:
    raw = path.read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="regenera el fichero (default)")
    mode.add_argument("--check", action="store_true", help="exit 0 si está al día, 1 si no")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="ruta al gateway-contract.openrpc.json")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                        help="ruta del Generated.kt a escribir/comprobar")
    args = parser.parse_args(argv)

    contract, source_hash = load_contract(args.contract)
    content = generate(contract, source_hash)

    if args.check:
        if args.output.is_file() and args.output.read_text(encoding="utf-8") == content:
            print(f"OK: {args.output} está al día con {args.contract}")
            return 0
        print(f"STALE: {args.output} no coincide con {args.contract} — regenera con --write")
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content, encoding="utf-8")
    print(f"Escrito {args.output} ({len(content.splitlines())} líneas)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GenerationError as exc:
        print(f"gen_android_contract: {exc}", file=sys.stderr)
        raise SystemExit(2)
