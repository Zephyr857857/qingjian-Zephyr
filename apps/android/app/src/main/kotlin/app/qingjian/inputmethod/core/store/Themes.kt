package app.qingjian.inputmethod.core.store

import android.graphics.Bitmap
import android.util.LruCache
import app.qingjian.inputmethod.core.Native
import java.nio.ByteBuffer
import org.json.JSONArray

/** 内置主题与设置页的预览图，都从 Rust 侧的主题库取（与 PC 同一份），壳不写死主题。 */
object Themes {
    val choices: List<ThemeChoice> by lazy {
        val json = runCatching { Native.themes() }.getOrNull() ?: return@lazy emptyList()
        val array = JSONArray(json)
        List(array.length()) { i ->
            val theme = array.getJSONObject(i)
            ThemeChoice(theme.getString("id"), theme.getString("name"))
        }
    }

    /** 主题名；不认识的 id 给缺省主题的名字。 */
    fun name(id: String): String =
        (choices.firstOrNull { it.id == id } ?: choices.firstOrNull())?.name.orEmpty()

    /**
     * 预览位图缓存：外观页对每个主题 × 深浅 × 字号档都画一张全宽 ARGB_8888 位图（一张约 0.6–1.5 MB）。
     * App 与输入法同进程，进程被系统长期保活；用 HashMap 缓存会随浏览主题只增不减，
     * 所以换成有内存上限的 LruCache，并在外观页退出、系统回收内存时 [clearPreviews]。
     */
    private val previews = object : LruCache<String, Bitmap?>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap?): Int = value?.allocationByteCount ?: 0
    }

    /** 清空预览位图缓存；外观页退出与 onTrimMemory 时调，避免位图常驻输入法进程。 */
    fun clearPreviews() = previews.evictAll()

    /**
     * 主题 [id] 的候选条样子（敲 nihao 的第一页），[scale] 是 dp → 像素的倍数；画过的记着。
     * 渲染器不能跨线程，只在主线程调（一张一两毫秒）。画不出为 null。
     */
    fun preview(id: String, dark: Boolean, scale: Float, textScale: Float): Bitmap? {
        val key = "$id/$dark/$scale/$textScale"
        previews.get(key)?.let { return it }
        val made = renderPreview(id, dark, scale, textScale)
        previews.put(key, made)
        return made
    }

    private fun renderPreview(id: String, dark: Boolean, scale: Float, textScale: Float): Bitmap? {
        val bytes = runCatching { Native.themePreview(id, dark, scale, textScale) }.getOrNull()
            ?: return null
        val header = ByteBuffer.wrap(bytes, 0, 8)
        val width = header.int
        val height = header.int
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 8, bytes.size - 8))
        }
    }
}
