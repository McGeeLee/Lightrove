package com.mcgeelee.photos.localization

import android.content.Context
import com.mcgeelee.photos.R
import java.util.Base64

/** Stable keys, rather than translated strings or unstable resource IDs, are persisted in WorkManager/Room. */
enum class RuntimeMessage(val resource: Int, val plural: Boolean = false) {
    MODEL_MISSING(R.string.runtime_model_missing),
    MODEL_VERIFYING(R.string.runtime_model_verifying),
    MODEL_INCOMPLETE(R.string.runtime_model_incomplete),
    MODEL_HASH_INVALID(R.string.runtime_model_hash_invalid),
    MODEL_VERIFY_FAILED(R.string.runtime_model_verify_failed),
    MODEL_DOWNLOAD_HTTP(R.string.runtime_model_download_http),
    MODEL_VERSION_MISMATCH(R.string.runtime_model_version_mismatch),
    MODEL_FILE_UNREADABLE(R.string.runtime_model_file_unreadable),
    MODEL_IMPORTING(R.string.runtime_model_importing),
    MODEL_DOWNLOADING(R.string.runtime_model_downloading),
    MODEL_NO_SPACE(R.string.runtime_model_no_space),
    MODEL_CANCELLED(R.string.runtime_model_cancelled),
    MODEL_INSTALL_FAILED(R.string.runtime_model_install_failed),
    MODEL_UNSUPPORTED(R.string.runtime_model_unsupported),
    MODEL_IMPORT_VERIFYING(R.string.runtime_model_import_verifying),
    MODEL_DOWNLOAD_VERIFYING(R.string.runtime_model_download_verifying),
    MODEL_WRONG_SIZE(R.string.runtime_model_wrong_size),
    MODEL_SHA_FAILED(R.string.runtime_model_sha_failed),
    MODEL_READY(R.string.runtime_model_ready),
    QUERY_EMPTY(R.string.runtime_query_empty),
    DOCUMENT_EMPTY(R.string.runtime_document_empty),
    IMAGE_ENCODING_FAILED(R.string.runtime_image_encoding_failed),
    MODEL_REQUIRED(R.string.runtime_model_required),
    VECTOR_INVALID(R.string.runtime_vector_invalid),
    VECTOR_EMPTY(R.string.runtime_vector_empty),
    AUDIO_LENGTH(R.string.runtime_audio_length),
    AUDIO_VALUES(R.string.runtime_audio_values),
    AUDIO_FORMAT(R.string.runtime_audio_format),
    AUDIO_TIMEOUT(R.string.runtime_audio_timeout),
    PCM_UNSUPPORTED(R.string.runtime_pcm_unsupported),
    LIBRARY_PERMISSION_CHANGED(R.string.runtime_library_permission_changed),
    LIBRARY_UNAVAILABLE(R.string.runtime_library_unavailable),
    VIDEO_UNREADABLE(R.string.runtime_video_unreadable),
    LIBRARY_FAILED(R.string.runtime_library_failed),
    MEDIA_REQUIRED(R.string.runtime_media_required),
    IMAGE_QUERY_LABEL(R.string.runtime_image_query_label),
    AUDIO_QUERY_LABEL(R.string.runtime_audio_query_label),
    IMAGE_UNREADABLE(R.string.runtime_image_unreadable),
    AUDIO_UNREADABLE(R.string.runtime_audio_unreadable),
    MULTIMODAL_REQUIRED(R.string.runtime_multimodal_required),
    QUERY_FAILED(R.string.runtime_query_failed),
    SEARCH_FAILED(R.string.runtime_search_failed),
    MATCH_TEXT_EXACT(R.string.runtime_match_text_exact),
    MATCH_TEXT_SEMANTIC(R.string.runtime_match_text_semantic),
    MATCH_IMAGE(R.string.runtime_match_image),
    MATCH_VIDEO(R.string.runtime_match_video),
    MATCH_AUDIO(R.string.runtime_match_audio),
    MATCH_FILENAME(R.string.runtime_match_filename),
    INDEX_PREPARING(R.string.runtime_index_preparing),
    INDEX_WAITING(R.string.runtime_index_waiting),
    INDEX_FAILED(R.string.runtime_index_failed),
    INDEX_CANCELLED(R.string.runtime_index_cancelled),
    INDEX_IDLE(R.string.runtime_index_idle),
    INDEX_COMPLETE(R.string.runtime_index_complete),
    INDEX_PROCESSING(R.string.runtime_index_processing),
    INDEX_FRAME(R.string.runtime_index_frame),
    INDEX_AUDIO(R.string.runtime_index_audio),
    INDEX_CHANNEL(R.string.runtime_index_channel),
    INDEX_NOTIFICATION(R.string.runtime_index_notification),
    STOP(R.string.runtime_stop),
    MEDIA_FAILED(R.string.runtime_media_failed),
    STAGE_TEXT_SEMANTIC(R.string.runtime_stage_text_semantic),
    STAGE_IMAGE_READING(R.string.runtime_stage_image_reading),
    STAGE_OCR(R.string.runtime_stage_ocr),
    STAGE_IMAGE_SEMANTIC(R.string.runtime_stage_image_semantic),
    STAGE_VIDEO_READING(R.string.runtime_stage_video_reading),
    STAGE_VIDEO_OCR(R.string.runtime_stage_video_ocr),
    STAGE_VIDEO_SEMANTIC(R.string.runtime_stage_video_semantic),
    STAGE_AUDIO_DECODING(R.string.runtime_stage_audio_decoding),
    STAGE_AUDIO_SEMANTIC(R.string.runtime_stage_audio_semantic),
    OCR_PARTIAL(R.string.runtime_ocr_partial),
    INDEX_WITH_RETRIES(R.string.runtime_index_with_retries),
    INDEX_PARTIAL(R.plurals.runtime_index_partial, plural = true),
    INDEX_RETRY_COUNT(R.plurals.runtime_index_retry_count, plural = true),
}

data class RuntimeText(val key: RuntimeMessage, val arguments: List<Any> = emptyList()) {
    fun render(context: Context): String {
        val localized = AppLanguage.localizedContext(context)
        return if (key.plural) {
            val count = (arguments.firstOrNull() as? Number)?.toInt() ?: 0
            localized.resources.getQuantityString(key.resource, count, *arguments.toTypedArray())
        } else localized.getString(key.resource, *arguments.toTypedArray())
    }

    /** Arguments are typed and URL-base64 encoded so names with separators/newlines remain intact. */
    fun encode(): String = PREFIX + key.name + arguments.joinToString(separator = "", prefix = "") { argument ->
        val tagged = if (argument is Number) "n${argument.toLong()}" else "s$argument"
        ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(tagged.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val PREFIX = "@runtime:v1:"
        fun decode(value: String?): RuntimeText? {
            if (value == null || !value.startsWith(PREFIX)) return null
            return runCatching {
                val parts = value.removePrefix(PREFIX).split(':')
                val key = RuntimeMessage.valueOf(parts.first())
                val arguments = parts.drop(1).map { part ->
                    val tagged = String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
                    when (tagged.firstOrNull()) {
                        'n' -> tagged.drop(1).toLong()
                        's' -> tagged.drop(1)
                        else -> error("Invalid runtime argument")
                    }
                }
                val validArguments = when (key) {
                    RuntimeMessage.MODEL_DOWNLOAD_HTTP, RuntimeMessage.PCM_UNSUPPORTED,
                    RuntimeMessage.INDEX_PARTIAL, RuntimeMessage.INDEX_RETRY_COUNT ->
                        arguments.size == 1 && arguments[0] is Long
                    RuntimeMessage.INDEX_PROCESSING -> arguments.size == 1 && arguments[0] is String
                    RuntimeMessage.INDEX_FRAME, RuntimeMessage.INDEX_AUDIO ->
                        arguments.size == 2 && arguments[0] is String && arguments[1] is Long
                    RuntimeMessage.INDEX_WITH_RETRIES -> arguments.size == 2 && arguments.all { it is String }
                    else -> arguments.isEmpty()
                }
                if (validArguments) RuntimeText(key, arguments) else null
            }.getOrNull()
        }

        fun fromError(error: Throwable, fallback: RuntimeMessage): RuntimeText =
            decode(error.message) ?: RuntimeText(fallback)

        /** Old releases persisted Chinese/SDK prose; replace it with the current localized fallback. */
        fun renderStored(value: String?, context: Context, fallback: RuntimeMessage): String =
            value?.lineSequence()?.map { decode(it) ?: RuntimeText(fallback) }
                ?.distinct()?.joinToString("\n") { it.render(context) }
                ?.takeIf { it.isNotBlank() } ?: RuntimeText(fallback).render(context)
    }
}

fun runtimeText(key: RuntimeMessage, vararg arguments: Any): RuntimeText = RuntimeText(key, arguments.toList())
