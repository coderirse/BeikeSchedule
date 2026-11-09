package com.caeamer.beikeschedule.ui.pet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PetAppearance(val id: String, val name: String, val asset: String, val columns: Int, val rows: Int)
object PetAppearances {
    val available = listOf(PetAppearance("deepseek", "大肥鱼 · deepseek酱", "pets/deepseek/spritesheet.webp", 8, 9))
}

/** 当前只缓存一个 atlas；不逐帧解码/裁出新 Bitmap，也不手动 recycle 仍可能被绘制的图像。 */
internal object PetAtlasCache {
    private val mutex = Mutex()
    private var cached: Pair<String, ImageBitmap>? = null
    suspend fun load(context: Context, appearance: PetAppearance): ImageBitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached?.takeIf { it.first == appearance.id }?.second ?: context.assets.open(appearance.asset).use { stream ->
                val bitmap = requireNotNull(BitmapFactory.decodeStream(stream)) { "宠物图片无法解码" }
                require(bitmap.width == 1536 && bitmap.height == 1872) { "宠物精灵图尺寸不匹配" }
                bitmap.asImageBitmap().also { cached = appearance.id to it }
            }
        }
    }
}
