package com.caeamer.beikeschedule.ui.pet.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 独立于云快照/核心 Room，整个文件加密；失败不会降级为明文，也不会自动覆盖旧文件。 */
class PetAiStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pet_ai/state.enc"))
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<PetAiState?>(null)
    val state = mutable.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { scope.launch { load() } }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey("beike_pet_ai", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("beike_pet_ai", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private suspend fun load() = lock.withLock {
        try {
            mutable.value = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) PetAiState() else {
                val data = file.readFully()
                require(data.size in 29..8_000_000 && data[0] == 1.toByte())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
                json.decodeFromString<PetAiState>(cipher.doFinal(data.copyOfRange(13, data.size)).decodeToString()).bounded()
            }
        } catch (_: Exception) { failure.value = "无法读取本机 AI 数据。可能是设备密钥失效或文件损坏；原文件已保留，可在设置中重置 AI 数据。" }
    }

    suspend fun update(transform: (PetAiState) -> PetAiState) = withContext(Dispatchers.IO) {
        lock.withLock {
            val previous = mutable.value ?: throw PetAiException(failure.value ?: "AI 设置正在加载，请稍后再试")
            val next = transform(previous).bounded()
            write(next)
            mutable.value = next
        }
    }

    private fun write(next: PetAiState) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.encodeToString(PetAiState.serializer(), next).toByteArray(Charsets.UTF_8))
            require(data.size <= 8_000_000)
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try { output.write(data); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        } catch (_: Exception) { throw PetAiException("无法加密保存 AI 数据，未写入明文。请检查设备锁屏密钥和可用空间。") }
    }

    suspend fun reset() = withContext(Dispatchers.IO) { lock.withLock {
        val next = PetAiState()
        if (failure.value != null) KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("beike_pet_ai") }
        write(next)
        mutable.value = next
        failure.value = null
    } }

    companion object {
        @Volatile private var instance: PetAiStore? = null
        fun get(context: Context): PetAiStore = instance ?: synchronized(this) {
            instance ?: PetAiStore(context.applicationContext).also { instance = it }
        }
    }
}
