"""Tests de ``scripts/gen_android_contract.py`` (tarea A3 del roadmap Android).

Cubren los tres contratos que exige ROADMAP §5:
  (a) cada schema de ``SCHEMAS`` produce una declaración Kotlin,
  (b) los campos opcionales del contrato salen nullable con default,
  (c) ``--check`` detecta un Generated.kt desactualizado (exit 1) y acepta
      uno al día (exit 0).
"""

import re
from pathlib import Path

import pytest

import scripts.gen_android_contract as gen

REPO_ROOT = Path(__file__).resolve().parents[2]
REAL_CONTRACT = REPO_ROOT / "apps/shared/src/gateway-contract.openrpc.json"
REAL_OUTPUT = (
    REPO_ROOT
    / "apps/android/core-contract/src/main/kotlin/ai/hermes/mama/contract/Generated.kt"
)


def _generate(tmp_path: Path) -> Path:
    """Genera Generated.kt en ``tmp_path`` sin tocar el fichero real."""
    out = tmp_path / "Generated.kt"
    rc = gen.main(
        ["--contract", str(REAL_CONTRACT), "--output", str(out), "--write"]
    )
    assert rc == 0
    assert out.is_file()
    return out


def test_every_schema_produces_a_kotlin_declaration(tmp_path):
    text = _generate(tmp_path).read_text(encoding="utf-8")
    for name in gen.SCHEMAS:
        assert re.search(
            rf"(?:data class|enum class|object|typealias|class)\s+{name}\b", text
        ), f"{name} no genera ninguna declaración"


def test_required_constants_objects_exist(tmp_path):
    text = _generate(tmp_path).read_text(encoding="utf-8")
    for obj in ("object RpcMethods", "object EventTypes", "object ServerRequests"):
        assert obj in text
    # Nombres de wire que la app usa (ROADMAP §2.3–2.5).
    for const in (
        '"session.list"',
        '"prompt.submit"',
        '"browser.controller.register"',
        '"message.delta"',
        '"approval"',
        '"clarify"',
    ):
        assert const in text


def test_optional_fields_are_nullable_with_default(tmp_path):
    text = _generate(tmp_path).read_text(encoding="utf-8")
    # anyOf [T, null] -> T? = null
    assert "val limit: Long? = null" in text  # SessionListParams.limit
    assert "val resolvedId: String? = null" in text  # SessionListRow.resolved_id
    assert "val storedSessionId: String? = null" in text
    # default JSON literal -> default Kotlin (no nullable)
    assert "val includeHidden: Boolean = false" in text
    # campo requerido sin default
    assert "val sessionId: String," in text
    # snake_case -> camelCase + @SerialName
    assert '@SerialName("session_id")' in text


def test_generated_header_and_hash(tmp_path):
    text = _generate(tmp_path).read_text(encoding="utf-8")
    assert text.startswith("// GENERATED — do not edit")
    assert "Contract SHA-256:" in text
    assert "!!" not in text  # regla dura: nada de !! en el Kotlin generado


def test_check_detects_staleness(tmp_path):
    out = _generate(tmp_path)
    check = ["--contract", str(REAL_CONTRACT), "--output", str(out), "--check"]
    assert gen.main(check) == 0
    # Desactualizado: contenido distinto -> exit 1 sin reescribir.
    out.write_text("// GENERATED — do not edit\n// stale\n", encoding="utf-8")
    assert gen.main(check) == 1
    assert out.read_text(encoding="utf-8").endswith("// stale\n")
    # Y regenerando vuelve a pasar.
    _generate(tmp_path)
    assert gen.main(check) == 0


def test_check_missing_output_is_stale(tmp_path):
    out = tmp_path / "Nope.kt"
    rc = gen.main(
        ["--contract", str(REAL_CONTRACT), "--output", str(out), "--check"]
    )
    assert rc == 1


def test_committed_generated_file_is_up_to_date():
    """Detector de drift: falla si Generated.kt no se regeneró tras tocar el contrato."""
    rc = gen.main(["--check"])
    assert rc == 0, "Generated.kt desactualizado: python3 scripts/gen_android_contract.py --write"


def test_unknown_schema_in_list_fails(tmp_path, monkeypatch):
    monkeypatch.setattr(gen, "SCHEMAS", [*gen.SCHEMAS, "NoExisteEnElContrato"])
    contract, _ = gen.load_contract(REAL_CONTRACT)
    with pytest.raises(gen.GenerationError):
        gen.generate(contract, "deadbeef")
