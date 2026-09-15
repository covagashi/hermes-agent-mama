package ai.hermes.mama.testing

import kotlin.system.exitProcess

/**
 * FakeGateway como proceso independiente (ROADMAP §5, B5):
 *
 * ```
 * ./gradlew :testing:run --args="--script hola_mundo"
 * ./gradlew :testing:run -Pscript=approval          # atajo Gradle
 * FAKE_SCRIPT=clarify ./gradlew :testing:run        # atajo env
 * ```
 *
 * Escucha en `0.0.0.0:8399` por defecto (el emulador lo alcanza vía `10.0.2.2`;
 * la app en flavor `dev` usa `ws://10.0.2.2:8399/api/ws` con el extra
 * `fake_script`). Credenciales del guion: `usuario` / `mama` salvo `auth`.
 */
object Main {
    private const val DEFAULT_PORT = 8399
    private const val DEFAULT_HOST = "0.0.0.0"
    private const val DEFAULT_SCRIPT = "hola_mundo"

    @JvmStatic
    @Suppress("MemberNameEqualsClassName") // `Main.main` es la convención de entry point.
    fun main(args: Array<String>) {
        val options =
            try {
                Options.parse(args)
            } catch (e: FakeScriptException) {
                System.err.println("FakeGateway: ${e.message}")
                exitProcess(2)
            }
        val script =
            try {
                FakeGatewayScript.load(options.script)
            } catch (e: FakeScriptException) {
                System.err.println("FakeGateway: ${e.message}")
                exitProcess(2)
            }
        val gateway =
            FakeGateway(script, host = options.host, requestedPort = options.port, logger = ::println)
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    gateway.close()
                },
                "fakegateway-shutdown",
            ),
        )
        gateway.start()
        println(
            "FakeGateway listo: script='${script.name}' " +
                "ws=ws://${gateway.clientHost}:${gateway.port}/api/ws http=${gateway.httpUrl} " +
                "(emulador: ws://10.0.2.2:${gateway.port}/api/ws)",
        )
        gateway.awaitTermination()
    }

    private enum class Flag {
        SCRIPT,
        HOST,
        PORT,
        HELP,
        UNKNOWN,
    }

    private data class Options(
        val script: String,
        val host: String,
        val port: Int,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                var script = System.getenv("FAKE_SCRIPT") ?: DEFAULT_SCRIPT
                var host = System.getenv("FAKE_HOST") ?: DEFAULT_HOST
                var port = parsePort(System.getenv("FAKE_PORT"), "FAKE_PORT") ?: DEFAULT_PORT
                var i = 0
                while (i < args.size) {
                    val arg = args[i]
                    i += 1
                    when (flagOf(arg)) {
                        Flag.SCRIPT -> {
                            script = args.valueAfter(i, arg)
                            i += 1
                        }

                        Flag.HOST -> {
                            host = args.valueAfter(i, arg)
                            i += 1
                        }

                        Flag.PORT -> {
                            port =
                                parsePort(args.valueAfter(i, arg), "--port")
                                    ?: throw FakeScriptException("imposible: --port sin valor")
                            i += 1
                        }

                        Flag.HELP -> {
                            println(HELP_TEXT)
                            exitProcess(0)
                        }

                        Flag.UNKNOWN -> {
                            System.err.println("argumento desconocido: $arg (prueba --help)")
                            exitProcess(2)
                        }
                    }
                }
                return Options(script, host, port)
            }

            private fun flagOf(arg: String): Flag =
                when {
                    arg == "--script" || arg == "-s" || arg.startsWith("--script=") -> Flag.SCRIPT
                    arg == "--host" || arg.startsWith("--host=") -> Flag.HOST
                    arg == "--port" || arg == "-p" || arg.startsWith("--port=") -> Flag.PORT
                    arg == "--help" || arg == "-h" -> Flag.HELP
                    else -> Flag.UNKNOWN
                }

            /** El valor tras el flag: `--flag valor` o `--flag=valor` en el propio arg. */
            private fun Array<String>.valueAfter(
                index: Int,
                flag: String,
            ): String {
                val inline = flag.substringAfter('=', "")
                if (inline.isNotEmpty()) {
                    return inline
                }
                return getOrNull(index)
                    ?: throw FakeScriptException("el flag $flag necesita un valor")
            }

            /** `--port abc` / `FAKE_PORT=abc` → error claro; `null` → sin override. */
            private fun parsePort(
                value: String?,
                origin: String,
            ): Int? {
                if (value == null) {
                    return null
                }
                return value.toIntOrNull()
                    ?: throw FakeScriptException("$origin: puerto inválido '$value' (esperaba un número)")
            }

            private val HELP_TEXT =
                """
                |FakeGateway standalone (B5)
                |  --script <nombre|ruta.json>   guion de testing/scripts/ (def. $DEFAULT_SCRIPT)
                |  --host <ip>                   bind (def. $DEFAULT_HOST)
                |  --port <n>                    puerto (def. $DEFAULT_PORT)
                |Env: FAKE_SCRIPT, FAKE_HOST, FAKE_PORT. Gradle: -Pscript=, -Pport=, -Phost=
                """.trimMargin()
        }
    }
}
