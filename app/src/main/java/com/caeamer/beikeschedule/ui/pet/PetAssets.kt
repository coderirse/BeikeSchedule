package com.caeamer.beikeschedule.ui.pet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
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
    private var poses: PetPoseAtlas? = null
    suspend fun loadPoses(context: Context): PetPoseAtlas = withContext(Dispatchers.IO) {
        mutex.withLock {
            poses ?: context.assets.open("pets/deepseek/poses.png").use { stream ->
                val bitmap = requireNotNull(BitmapFactory.decodeStream(stream))
                require(bitmap.width % 2 == 0 && bitmap.height % 2 == 0 && bitmap.width <= 2048 && bitmap.height <= 2048)
                val w = bitmap.width / 2; val h = bitmap.height / 2
                val regions = (0..1).map { row ->
                    var left = w; var top = h; var right = 0; var bottom = 0
                    for (column in 0..1) {
                        val pixels = IntArray(w * h)
                        bitmap.getPixels(pixels, 0, w, column * w, row * h, w, h)
                        pixels.forEachIndexed { index, color ->
                            if (color ushr 24 > 24) {
                                val x = index % w; val y = index / w
                                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                            }
                        }
                    }
                    require(right > left && bottom > top)
                    IntRect(left, top, right, bottom)
                }
                PetPoseAtlas(bitmap.asImageBitmap(), w, h, regions).also { poses = it }
            }
        }
    }
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

/** 两行新姿态，各含睁眼/眨眼；联合透明边界保证动作接触面不随眨眼漂移。 */
internal data class PetPoseAtlas(val image: ImageBitmap, val cellWidth: Int, val cellHeight: Int, val regions: List<IntRect>)
