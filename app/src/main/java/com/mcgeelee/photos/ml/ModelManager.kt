package com.mcgeelee.photos.ml

import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.RuntimeText
import com.mcgeelee.photos.localization.runtimeText
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ModelPhase { MISSING, VERIFYING, DOWNLOADING, IMPORTING, READY, ERROR }

data class ModelStatus(
    val phase: ModelPhase = ModelPhase.MISSING,
    val bytesCopied: Long = 0,
    val totalBytes: Long = ModelManager.MODEL_BYTES,
    val message: String = runtimeText(RuntimeMessage.MODEL_MISSING).encode(),
) {
    val progress: Float get() = if (totalBytes > 0) (bytesCopied.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    val isBusy: Boolean get() = phase in setOf(ModelPhase.VERIFYING, ModelPhase.DOWNLOADING, ModelPhase.IMPORTING)
}

/** App-scoped owner of a checksum-verified, private model file. Never downloads on startup. */
class ModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, "models").apply { mkdirs() }
    private val installedFile = File(directory, MODEL_FILE_NAME)
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(ModelStatus())
    val status: StateFlow<ModelStatus> = mutableStatus.asStateFlow()

    @Volatile private var verified = false
    val modelReady: Boolean get() = verified && installedFile.length() == MODEL_BYTES
    val modelPath: String? get() = installedFile.absolutePath.takeIf { modelReady }

    init { scope.launch { refresh() } }

    /** Hash at most once per process, even if several consumers request readiness together. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (modelReady) return@withLock
            if (!installedFile.isFile) {
                mutableStatus.value = ModelStatus()
                return@withLock
            }
            mutableStatus.value = ModelStatus(ModelPhase.VERIFYING, message = runtimeText(RuntimeMessage.MODEL_VERIFYING).encode())
            try {
                require(installedFile.length() == MODEL_BYTES) { runtimeText(RuntimeMessage.MODEL_INCOMPLETE).encode() }
                installedFile.inputStream().buffered().use { input ->
                    val actualHash = digest(input)
                    require(actualHash == MODEL_SHA256) { runtimeText(RuntimeMessage.MODEL_HASH_INVALID).encode() }
                }
                verified = true
                mutableStatus.value = readyStatus()
            } catch (cancelled: CancellationException) {
                mutableStatus.value = ModelStatus()
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = ModelStatus(ModelPhase.ERROR, message = RuntimeText.fromError(error, RuntimeMessage.MODEL_VERIFY_FAILED).encode())
            }
        }
    }

    /** Call only after the user explicitly chooses the 485 MB download action. */
    suspend fun downloadOfficial() = install(ModelPhase.DOWNLOADING) { partial ->
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            connection.connect()
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                runtimeText(RuntimeMessage.MODEL_DOWNLOAD_HTTP, connection.responseCode).encode()
            }
            val advertisedSize = connection.contentLengthLong
            require(advertisedSize < 0 || advertisedSize == MODEL_BYTES) { runtimeText(RuntimeMessage.MODEL_VERSION_MISMATCH).encode() }
            connection.inputStream.buffered().use { copyVerified(it, partial, ModelPhase.DOWNLOADING) }
        } finally {
            connection.disconnect()
        }
    }

    /** SAF file access only; importing never needs broad storage permissions. */
    suspend fun importModel(uri: Uri) = install(ModelPhase.IMPORTING) { partial ->
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(runtimeText(RuntimeMessage.MODEL_FILE_UNREADABLE).encode())
        input.buffered().use { copyVerified(it, partial, ModelPhase.IMPORTING) }
    }

    private suspend fun install(phase: ModelPhase, copy: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (modelReady) {
                mutableStatus.value = readyStatus()
                return@withLock
            }
            val partial = File(directory, "$MODEL_FILE_NAME.partial")
            mutableStatus.value = ModelStatus(phase, message = if (phase == ModelPhase.IMPORTING) runtimeText(RuntimeMessage.MODEL_IMPORTING).encode() else runtimeText(RuntimeMessage.MODEL_DOWNLOADING).encode())
            try {
                require(directory.usableSpace > MODEL_BYTES + 32L * 1024 * 1024) { runtimeText(RuntimeMessage.MODEL_NO_SPACE).encode() }
                partial.delete()
                copy(partial)
                currentCoroutineContext().ensureActive()
                // Same-directory atomic rename keeps incomplete files invisible to the engine.
                Files.move(partial.toPath(), installedFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                verified = true
                mutableStatus.value = readyStatus()
            } catch (cancelled: CancellationException) {
                mutableStatus.value = if (modelReady) readyStatus() else ModelStatus(message = runtimeText(RuntimeMessage.MODEL_CANCELLED).encode())
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = ModelStatus(ModelPhase.ERROR, message = RuntimeText.fromError(error, RuntimeMessage.MODEL_INSTALL_FAILED).encode())
                throw error
            } finally {
                partial.delete()
            }
        }
    }

    private suspend fun copyVerified(input: InputStream, destination: File, phase: ModelPhase) {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var copied = 0L
        var lastPublished = 0L
        FileOutputStream(destination).use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= MODEL_BYTES) { runtimeText(RuntimeMessage.MODEL_UNSUPPORTED).encode() }
                sha.update(buffer, 0, count)
                output.write(buffer, 0, count)
                if (copied - lastPublished >= 1024 * 1024) {
                    mutableStatus.value = ModelStatus(phase, copied, message = if (phase == ModelPhase.IMPORTING) runtimeText(RuntimeMessage.MODEL_IMPORT_VERIFYING).encode() else runtimeText(RuntimeMessage.MODEL_DOWNLOAD_VERIFYING).encode())
                    lastPublished = copied
                }
            }
            output.fd.sync()
        }
        require(copied == MODEL_BYTES) { runtimeText(RuntimeMessage.MODEL_WRONG_SIZE).encode() }
        require(sha.digest().toHex() == MODEL_SHA256) { runtimeText(RuntimeMessage.MODEL_SHA_FAILED).encode() }
    }

    private suspend fun digest(input: InputStream): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return sha.digest().toHex()
            sha.update(buffer, 0, count)
        }
    }

    private fun readyStatus() = ModelStatus(ModelPhase.READY, MODEL_BYTES, message = runtimeText(RuntimeMessage.MODEL_READY).encode())
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val MODEL_FILE_NAME = "embeddinggemma-2-740m.litertlm"
        const val MODEL_BYTES = 484_622_336L
        const val MODEL_SHA256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"
        // v3 includes AAC priming support and all five OCR scripts; old multimodal records must be rebuilt.
        const val MODEL_ID = "embeddinggemma-2-740m-e7a8a2204b91-768-v3-ocr-five-scripts-v2"
        const val MODEL_URL = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm"
        const val MODEL_PAGE = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm"
    }
}
