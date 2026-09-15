package ai.hermes.mama.gateway

import ai.hermes.mama.contract.AttachedImageResult
import ai.hermes.mama.contract.FileAttachParams
import ai.hermes.mama.contract.FileAttachResult
import ai.hermes.mama.contract.ImageAttachBytesParams
import ai.hermes.mama.contract.PromptSubmitParams
import ai.hermes.mama.contract.PromptSubmitResult
import ai.hermes.mama.contract.RpcMethods
import kotlinx.serialization.json.JsonPrimitive

/*
 * Métodos de prompt y adjuntos de §2.3 sobre GatewayClient: la versión con
 * DTO cubre todo el contrato; la sobrecarga fija la forma de la app
 * (`surface:"android"` en `prompt.submit`).
 */

/** `prompt.submit` — enviar un mensaje (el result trae `status`: streaming/queued/steered/redirected). */
suspend fun GatewayClient.submitPrompt(params: PromptSubmitParams): PromptSubmitResult =
    rpc(RpcMethods.PROMPT_SUBMIT, params, PromptSubmitParams.serializer(), PromptSubmitResult.serializer())

/** `prompt.submit` con la forma de la app: `{session_id, text, surface:"android", voice_context?}` (§2.3). */
suspend fun GatewayClient.submitPrompt(
    sessionId: String,
    text: String,
    voiceContext: String? = null,
): PromptSubmitResult =
    submitPrompt(
        PromptSubmitParams(
            sessionId = sessionId,
            text = JsonPrimitive(text),
            surface = GatewayClient.APP_SOURCE,
            voiceContext = voiceContext,
        ),
    )

/** `image.attach_bytes` — foto que se adjunta al **siguiente** `prompt.submit` (§2.3). */
suspend fun GatewayClient.attachImage(params: ImageAttachBytesParams): AttachedImageResult =
    rpc(RpcMethods.IMAGE_ATTACH_BYTES, params, ImageAttachBytesParams.serializer(), AttachedImageResult.serializer())

/** `image.attach_bytes` con bytes en base64. */
suspend fun GatewayClient.attachImage(
    sessionId: String,
    contentBase64: String,
    filename: String,
): AttachedImageResult =
    attachImage(
        ImageAttachBytesParams(
            sessionId = sessionId,
            contentBase64 = contentBase64,
            filename = filename,
        ),
    )

/** `file.attach` — PDF/otros; el `ref_text` del result se incluye en el texto del prompt (§2.3). */
suspend fun GatewayClient.attachFile(params: FileAttachParams): FileAttachResult =
    rpc(RpcMethods.FILE_ATTACH, params, FileAttachParams.serializer(), FileAttachResult.serializer())

/** `file.attach` por `data_url`. */
suspend fun GatewayClient.attachFile(
    sessionId: String,
    dataUrl: String,
    name: String,
): FileAttachResult = attachFile(FileAttachParams(sessionId = sessionId, dataUrl = dataUrl, name = name))
