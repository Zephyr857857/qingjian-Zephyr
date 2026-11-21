// Modified by Zephyr857857 on 2026-11-21 (upstream: qingjian-team/qingjian @ 996350c). See FORK.md.
package app.qingjian.inputmethod.perf

import kotlin.math.roundToInt

/**
 * 按键链路耗时探针（诊断版专用）。主线程调用，无锁。
 *
 * 一次 onKey 分为三段：
 *  - [begin] 到 [markEngine]：组句引擎 push（JNI）；
 *  - [markEngine] 到 [markComposing]：apply 里的输入连接（跨进程 Binder）；
 *  - [markComposing] 到 [end]：showComposition 触发的候选条全量渲染（JNI + 位图拷贝）。
 * [end] 记一次按键的总时长与三段，存进环形窗口，按中位数 / P90 汇总，避开偶发 GC 尖刺。
 */
object QjPerf {
    /** 诊断版开；定稿改成 false（或删掉本文件与调用点）即零开销。 */
    const val ENABLED = false

    private const val CAP = 120
    private val totals = IntArray(CAP)
    private val engines = IntArray(CAP)
    private val ic = IntArray(CAP)
    private val render = IntArray(CAP)
    private var size = 0
    private var cursor = 0

    private var t0 = 0L
    private var tEngine = 0L
    private var tComposing = 0L

    private val now: Long get() = System.nanoTime()

    fun begin() {
        if (!ENABLED) return
        t0 = now
        tEngine = 0L
        tComposing = 0L
    }

    /** 组句引擎调用返回后。 */
    fun markEngine() {
        if (!ENABLED || t0 == 0L) return
        tEngine = now
    }

    /** 输入连接改完、showComposition 之前。 */
    fun markComposing() {
        if (!ENABLED || t0 == 0L) return
        tComposing = now
    }

    /** 这一笔结束（showComposition 之后）；[label] 只用于最近一笔的文字提示。 */
    fun end(label: String) {
        if (!ENABLED || t0 == 0L) return
        val end = now
        // 三段都可能没走到（例如直接 commit、没进组句）：没走到就记 0，不留上次残留
        val engineMs = if (tEngine > t0) (tEngine - t0) / 1_000_000.0 else 0.0
        val icMs = if (tComposing > tEngine && tEngine > 0L) (tComposing - tEngine) / 1_000_000.0 else 0.0
        val renderMs = if (tComposing > 0L && end > tComposing) (end - tComposing) / 1_000_000.0 else 0.0
        totals[cursor] = (engineMs + icMs + renderMs).roundToInt()
        engines[cursor] = engineMs.roundToInt()
        ic[cursor] = icMs.roundToInt()
        render[cursor] = renderMs.roundToInt()
        cursor = (cursor + 1) % CAP
        if (size < CAP) size++
        t0 = 0L
        lastLabel = label
    }

    /** 诊断被打断（切键、上滑、长按取消等）时丢弃这一笔。 */
    fun cancel() {
        t0 = 0L
    }

    var lastLabel: String = ""
        private set

    /** 最近一笔的分段（ms），给候选条上的即时行；null 是还没测到。 */
    fun last(): List<Int>? = if (!ENABLED || size == 0) null else {
        val i = (cursor - 1 + CAP) % CAP
        listOf(totals[i], engines[i], ic[i], render[i])
    }

    /**
     * 汇总行：总数与三段各自的 中位数 / P90（ms）。
     * 返回形如 "键 中位 12 / 组3 连4 渲5 · P90 21 / 组6 连7 渲8"。
     */
    fun summary(): String {
        if (!ENABLED || size == 0) return "按键探针：打字试试…"
        fun pct(a: IntArray, q: Double): Int {
            val sorted = a.copyOfRange(0, size).sorted()
            val idx = ((sorted.size - 1) * q).toInt()
            return sorted[idx]
        }
        val tm = pct(totals, 0.5); val tp = pct(totals, 0.9)
        val em = pct(engines, 0.5); val ep = pct(engines, 0.9)
        val im = pct(ic, 0.5); val ip = pct(ic, 0.9)
        val rm = pct(render, 0.5); val rp = pct(render, 0.9)
        // 两行各 30 字以内，候选条上放得下：中位 / P90，括号里是 组(engine) 连(InputConnection) 渲(render)
        return "中位${tm} 组$em 连$im 渲$rm · P90${tp} 组$ep 连$ip 渲$rp · n$size"
    }

    fun count(): Int = size

    fun reset() {
        size = 0
        cursor = 0
        t0 = 0L
    }
}
