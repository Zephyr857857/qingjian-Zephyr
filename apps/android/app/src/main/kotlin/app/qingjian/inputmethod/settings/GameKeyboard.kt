package app.qingjian.inputmethod.settings

import android.content.Context

/**
 * 游戏键盘的设置：在勾上的应用里换成半透明的悬浮小键盘（[app.qingjian.inputmethod.service.GameFrame]）。
 * 与 [Settings] 同一个 SharedPreferences 文件，设置页改了开着的键盘照样重建；位置与见过的应用由服务自己写。
 */
data class GameKeyboard(
    /** 总开关；关了勾着的应用也用普通键盘。 */
    val enabled: Boolean,
    /** 透明度百分比，0 不透明。 */
    val transparency: Int,
    /** 键盘宽度占屏宽的百分比（连续，拖动栏的缩放手柄可改）。 */
    val widthPercent: Int,
    /** 键盘行高百分比（100 是标准高度，连续）。 */
    val heightPercent: Int,
    /** 候选旁边显示译词（缺省关：游戏里屏幕小，只要字）。 */
    val glosses: Boolean,
    /** 用游戏键盘的应用（包名）。 */
    val apps: Set<String>,
) {
    /** 打过字的一个应用：包名与当时取到的名字。 */
    data class SeenApp(val packageName: String, val label: String)

    companion object {
        const val KEY_ENABLED = "game_keyboard"
        const val KEY_TRANSPARENCY = "game_transparency"
        const val KEY_WIDTH = "game_width"
        const val KEY_HEIGHT = "game_height"
        const val KEY_GLOSSES = "game_glosses"
        const val KEY_APPS = "game_apps"
        /** 旧三档大小，只用来把老设置迁到连续值。 */
        private const val KEY_SIZE = "game_size"
        private const val KEY_SEEN = "game_seen"
        private const val KEY_POSITION_PREFIX = "game_position:"
        private const val KEY_HINTED = "game_hinted"

        const val TRANSPARENCY_MAX = 70
        const val WIDTH_MIN = 35
        const val WIDTH_MAX = 85
        const val HEIGHT_MIN = 60
        const val HEIGHT_MAX = 120
        private const val DEFAULT_TRANSPARENCY = 40
        private const val DEFAULT_WIDTH = 50
        private const val DEFAULT_HEIGHT = 70

        /** 见过的应用最多记这么多，多了丢最早的。 */
        private const val SEEN_LIMIT = 60

        fun load(context: Context): GameKeyboard {
            val prefs = Settings.preferences(context)
            // 老版本只有小/中/大三档；没写过新键时按旧档给个起点，之后就是连续值
            val (fallbackWidth, fallbackHeight) = when (prefs.getString(KEY_SIZE, null)) {
                "SMALL" -> 42 to 62
                "LARGE" -> 60 to 82
                else -> DEFAULT_WIDTH to DEFAULT_HEIGHT
            }
            return GameKeyboard(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                transparency = prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY).coerceIn(0, TRANSPARENCY_MAX),
                widthPercent = prefs.getInt(KEY_WIDTH, fallbackWidth).coerceIn(WIDTH_MIN, WIDTH_MAX),
                heightPercent = prefs.getInt(KEY_HEIGHT, fallbackHeight).coerceIn(HEIGHT_MIN, HEIGHT_MAX),
                glosses = prefs.getBoolean(KEY_GLOSSES, false),
                apps = prefs.getStringSet(KEY_APPS, null).orEmpty().toSet(),
            )
        }

        /** 现在给 [packageName] 用游戏键盘吗：总开关开着、这个应用勾上了（横竖屏都算）。 */
        fun wanted(context: Context, packageName: String?): Boolean {
            if (packageName == null) return false
            val game = load(context)
            return game.enabled && packageName in game.apps
        }

        fun setApp(context: Context, packageName: String, on: Boolean) {
            val prefs = Settings.preferences(context)
            val apps = prefs.getStringSet(KEY_APPS, null).orEmpty().toMutableSet()
            if (on) apps += packageName else apps -= packageName
            prefs.edit().putStringSet(KEY_APPS, apps).apply()
        }

        /** 打过字的应用，最近的在前。一行一个「包名\t名字」，按时间先后排。 */
        fun seen(context: Context): List<SeenApp> =
            Settings.preferences(context).getString(KEY_SEEN, null).orEmpty().lines()
                .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { SeenApp(it[0], it[1]) } }
                .reversed()

        /** 记下打过字的 [app]；已经记过返回 false，不写。 */
        fun remember(context: Context, app: SeenApp): Boolean {
            val list = seen(context).reversed()
            if (list.any { it.packageName == app.packageName }) return false
            val kept = (list + app).takeLast(SEEN_LIMIT)
            Settings.preferences(context).edit()
                .putString(KEY_SEEN, kept.joinToString("\n") { "${it.packageName}\t${it.label.replace('\t', ' ').replace('\n', ' ')}" })
                .apply()
            return true
        }

        /** [packageName] 里键盘的位置：左上角在可挪范围里的比例（0..1）；没记过为 null。 */
        fun position(context: Context, packageName: String): Pair<Float, Float>? {
            val value = Settings.preferences(context).getString(KEY_POSITION_PREFIX + packageName, null) ?: return null
            val parts = value.split(',').mapNotNull(String::toFloatOrNull)
            return if (parts.size == 2) parts[0].coerceIn(0f, 1f) to parts[1].coerceIn(0f, 1f) else null
        }

        fun savePosition(context: Context, packageName: String, x: Float, y: Float) {
            Settings.preferences(context).edit().putString(KEY_POSITION_PREFIX + packageName, "$x,$y").commit()
        }

        /** 提示过「这个应用可以开悬浮键盘」的包名，每个只提示一次，别每次进都弹。 */
        fun hinted(context: Context): Set<String> =
            Settings.preferences(context).getStringSet(KEY_HINTED, null).orEmpty()

        fun markHinted(context: Context, packageName: String) {
            val prefs = Settings.preferences(context)
            val set = prefs.getStringSet(KEY_HINTED, null).orEmpty().toMutableSet()
            set += packageName
            prefs.edit().putStringSet(KEY_HINTED, set).apply()
        }
    }
}
