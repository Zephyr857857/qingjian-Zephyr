package app.qingjian.inputmethod.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.perf.QjPerf
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.popup.ChoicePopup
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.theme.Palette

/**
 * 键盘上方那一条，三种样子：组句中是候选（[CandidateStrip]，位图来自 [source]，横向滑动、长按弹译词气泡），
 * 平时是工具栏（[Toolbar]，中间那排可横滑），调高度时是拖动手柄（[ResizeHandle]）。
 */
@SuppressLint("ViewConstructor")
class CandidateBarView(
    context: Context,
    private val palette: Palette,
    /** 候选字号档的倍数：调大时条跟着高，候选不被截。 */
    textScale: Float,
    private val source: StripSource,
    /** 工具栏中间那排放的工具（用户定制的）。 */
    tools: List<ToolbarTool>,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun onSelect(index: Int)

        /** 点了拼音行：光标挪到第 [index] 个字之后。 */
        fun onMoveCaret(index: Int)

        /** 长按气泡里选了译词：上屏第 [index] 个候选的第 [sense] 条译文。 */
        fun onSelectTranslation(index: Int, sense: Int)

        /** 长按气泡里选了「删除」：删第 [index] 个候选（用户词、学习记录）。 */
        fun onForget(index: Int)

        /** 工具栏中间那排点了一个工具；调高度在这里自己处理，不会传出去。 */
        fun onToolbar(tool: ToolbarTool)

        /** 工具栏左端的 logo：进功能面板。 */
        fun openFunctions()

        /** 工具栏右端、以及组句时候选栏右端的 ⌄：收起键盘。 */
        fun hideKeyboard()

        /** 拖动调高度：[deltaPx] 是手指相对按下处往上走的距离，[done] 表示手指抬起。 */
        fun onResizeDrag(deltaPx: Float, done: Boolean)

        /** 在候选栏上向下滑：展开全部候选（[CandidateGrid]）。 */
        fun onExpand()
    }

    private val density = resources.displayMetrics.density
    val barHeight = 62f * density * maxOf(1f, textScale)
    private val strip = CandidateStrip(density, barHeight, textScale, palette.gloss)
    private val toolbar = Toolbar(context, palette, density, barHeight, tools)
    private val resizeHandle = ResizeHandle(
        palette, density, barHeight,
        onDrag = listener::onResizeDrag,
        onDone = { resizing = false },
    )

    private var resizing = false
        set(value) {
            field = value
            invalidate()
        }

    private val popup = ChoicePopup(this, palette)

    private val divider = Paint().apply {
        color = palette.faint
        strokeWidth = maxOf(1f, density * 0.7f)
    }

    // 诊断版：候选条顶部叠一行耗时统计
    private val diagBg = Paint().apply { color = 0xB3000000.toInt() }
    private val diagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 10f * density
    }

    /** 候选右端收起键那一格的宽；候选滚到它底下为止。 */
    private val expandSlot = 46f * density
    private val expandFill = Paint()
    private val expandIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.text }

    /** 在候选栏上向下滑多少算「展开全部候选」。 */
    private val swipeExpandPx = 28f * density

    /** 本次触摸是否已判定为下滑展开，避免重复触发、也拦住随后的横向 fling。 */
    private var swipeExpanded = false

    /** 有候选时右端显示收起键。 */
    private val expandable: Boolean get() = strip.candidates.isNotEmpty()

    /** 显示的是工具栏：横滑滑的是它中间那排图标，不是整条。 */
    /** 工具栏位置上的一句提示与点它要做的事；null 是没有提示。几秒后、出候选或点过就收起。 */
    private var notice: Pair<String, () -> Unit>? = null
    private val clearNotice = Runnable {
        notice = null
        invalidate()
    }

    private val toolbarShown: Boolean get() = !resizing && strip.isEmpty && notice == null

    /** 候选能用的宽度：有收起键时让出那一格。 */
    private val stripWidth: Int get() = if (expandable) width - expandSlot.toInt() else width

    /** 气泡对着的候选下标；气泡开着时高亮跟到它上面。 */
    private var popupIndex = -1

    /** 气泡里「删除」那一项的下标；这个候选删不了时为 -1。 */
    private var forgetItem = -1

    /** 气泡里只有「删除」（候选没有译文）。 */
    private var forgetOnly = false

    private val scroller = OverScroller(context)

    /** 这一下按在拼音行上：左右拖是挪光标，不滚候选、不弹译词。 */
    private var onPreedit = false

    /** 拖动中光标上次挪到的位置（拼音行第几个字之后）；还没挪过为 -1。 */
    private var dragCaret = -1

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (onPreedit) {
                dragCaret(e2.x)
                return true
            }
            if (toolbarShown) {
                toolbar.scrollTo(toolbar.offset + dx, width)
                invalidate()
                return true
            }
            // 下滑展开全部候选（对标搜狗「下滑展开更多候选」）；横向滑动仍是滚候选，两者按主轴区分
            if (!swipeExpanded && e1 != null) {
                val totalDy = e2.y - e1.y
                val totalDx = e2.x - e1.x
                if (totalDy > swipeExpandPx && totalDy > kotlin.math.abs(totalDx) * 1.2f) {
                    swipeExpanded = true
                    scroller.forceFinished(true)
                    Haptics.tap(context)
                    listener.onExpand()
                    return true
                }
            }
            if (swipeExpanded) return true
            scrollTo((scrollX + dx).toInt().coerceIn(0, maxScroll()), 0)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (onPreedit || swipeExpanded) return true
            if (toolbarShown) {
                scroller.fling(toolbar.offset.toInt(), 0, -vx.toInt(), 0, 0, toolbar.maxOffset(width).toInt(), 0, 0)
            } else {
                scroller.fling(scrollX, 0, -vx.toInt(), 0, 0, maxScroll(), 0, 0)
            }
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) = openTranslations(e)

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }
    })

    init {
        setBackgroundColor(palette.board)
    }

    /** 拼音行右侧的提示（删候选的结果）由渲染器从 Rust 那边的帧里取，这里只要拼音和候选。 */
    fun show(preedit: String, candidates: List<Candidate>) {
        closePopup()
        if (preedit.isNotEmpty() || candidates.isNotEmpty()) clearNotice.run()
        val wasEmpty = strip.isEmpty
        strip.show(preedit, candidates)
        scroller.forceFinished(true)
        scrollTo(0, 0)
        // 工具栏→候选栏的首次切换必须同步画位图：数据先于位图变，异步会露出
        // 一帧「数据非空、位图还是 null」的空白，看起来就是候选栏闪一下。
        // 候选栏已经在的连续输入仍走异步，保留快打流畅。
        if (wasEmpty && !strip.isEmpty) {
            removeCallbacks(pendingRender)
            pendingRender.run()
        } else {
            repaint()
        }
    }

    /** 在工具栏的位置放一句提示 [message]，点它执行 [action]（打开对应的设置页）。 */
    fun showNotice(message: String, action: () -> Unit) {
        notice = message to action
        removeCallbacks(clearNotice)
        postDelayed(clearNotice, NOTICE_MS)
        invalidate()
    }

    /** 进入拖动调高度（功能面板里点「键盘高度」）。 */
    fun startResize() {
        resizing = true
    }

    /**
     * 按当前高亮重画位图：新的一帧到了、或长按气泡换了对着的候选。
     * 栅格化（Rust renderStrip）是按键链路上最重的一段，挪到下一次消息循环做，
     * 并合并一帧内的多次请求（快打时几键只画最后一次）。Rust 侧候选内容在 push 时已备好，
     * 这里只读当前 popupIndex / stripWidth，天然取最新，合并不会画旧帧。
     */
    private fun repaint() {
        removeCallbacks(pendingRender)
        post(pendingRender)
    }

    private val pendingRender = Runnable {
        strip.paint(if (strip.isEmpty) null else source.render(popupIndex.coerceAtLeast(0), palette.dark, stripWidth))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight.toInt())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            if (toolbarShown) toolbar.scrollTo(scroller.currX.toFloat(), width) else scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        when {
            resizing -> resizeHandle.draw(canvas, width)
            strip.isEmpty && notice != null -> toolbar.drawNotice(canvas, width, notice!!.first)
            strip.isEmpty -> toolbar.draw(canvas, width)
            else -> {
                strip.draw(canvas, scrollX, stripWidth)
                if (expandable) drawHide(canvas)
                // 候选条与键盘之间一道细线，分出层次；滚动时线不跟着走
                canvas.drawRect(scrollX.toFloat(), height - divider.strokeWidth, scrollX + width.toFloat(), height.toFloat(), divider)
            }
        }
        if (QjPerf.ENABLED) drawDiag(canvas)
    }

    /** 诊断版：在候选条顶部画两行耗时（最近一笔 + 中位数/P90 汇总），看清「慢半拍」卡在哪一段。 */
    private fun drawDiag(canvas: Canvas) {
        val line1 = QjPerf.summary()
        val last = QjPerf.last()
        val line2 = if (last != null) "本键${last[0]}ms 组${last[1]} 连${last[2]} 渲${last[3]}" else ""
        val pad = 4f * density
        val lh = 12f * density
        val boxH = pad * 2 + lh * 2
        canvas.drawRect(0f, 0f, width.toFloat(), boxH, diagBg)
        // 宽屏/窄屏都保证整行可见：超宽就横向压缩，不裁字
        fun line(text: String, size: Float, y: Float) {
            if (text.isEmpty()) return
            diagText.textSize = size
            val w = diagText.measureText(text)
            val avail = width - pad * 2
            canvas.save()
            if (w > avail) canvas.scale(avail / w, 1f, pad, y)
            canvas.drawText(text, pad, y, diagText)
            canvas.restore()
        }
        line(line1, 9.5f * density, pad + lh * 0.8f)
        line(line2, 10.5f * density, pad + lh * 1.85f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = when {
        resizing -> resizeHandle.onTouch(event, width)
        popup.showing -> onPopupTouch(event)
        else -> {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                swipeExpanded = false
                onPreedit = !strip.isEmpty && strip.caretAt(event.x, event.y) >= 0
                dragCaret = -1
                // 长按一触发 GestureDetector 就不再给拖动；按在拼音行上先停一停再拖也要跟
                gestures.setIsLongpressEnabled(!onPreedit)
            }
            gestures.onTouchEvent(event)
        }
    }

    /** 钉在右端的收起键：与候选行同一高度居中，左边一道短竖线隔开。 */
    private fun drawHide(canvas: Canvas) {
        val left = scrollX + stripWidth.toFloat()
        expandFill.color = strip.background
        canvas.drawRect(left, 0f, scrollX + width.toFloat(), height.toFloat(), expandFill)
        val cy = (strip.candidatesTop + height) / 2f
        canvas.drawRect(left, cy - 12 * density, left + divider.strokeWidth, cy + 12 * density, divider)
        PanelIcons.draw(canvas, PanelIcons.Icon.HIDE, left + expandSlot / 2, cy, 24 * density, expandIcon)
    }

    /**
     * 沿拼音行拖：光标跟着手指落到最近的字缝，每过一格轻震一下（#881）。
     * 拼音行按视图坐标画、不随候选滚动；新的一帧到了缝的位置会变，所以每次按手指重新找。
     */
    private fun dragCaret(x: Float) {
        val caret = strip.caretNear(x)
        if (caret < 0 || caret == dragCaret) return
        dragCaret = caret
        Haptics.tap(context)
        listener.onMoveCaret(caret)
    }

    private fun tap(x: Float, y: Float) {
        if (expandable && x >= stripWidth) {
            Haptics.tap(context)
            listener.hideKeyboard()
            return
        }
        // 拼音行钉在视口上，不随候选滚动，按视图坐标查
        val caret = strip.caretAt(x, y)
        if (caret >= 0) {
            listener.onMoveCaret(caret)
            return
        }
        if (!strip.isEmpty) {
            strip.indexAt(x + scrollX).takeIf { it >= 0 }?.let { index ->
                Haptics.tap(context)
                listener.onSelect(index)
            }
            return
        }
        notice?.let { (_, action) ->
            clearNotice.run()
            action()
            return
        }
        when {
            toolbar.onLogo(x) -> listener.openFunctions()
            toolbar.onHide(x, width) -> listener.hideKeyboard()
            else -> when (val tool = toolbar.toolAt(x, width)) {
                null -> Unit
                ToolbarTool.RESIZE -> resizing = true
                else -> listener.onToolbar(tool)
            }
        }
    }

    /**
     * 长按候选：弹出它的译词，第一条对着手指；删得掉的候选（用户词、学过的词）末尾再跟一项「删除」，
     * 对应桌面端修饰键 + 数字。没有译文也删不了的候选不弹。
     */
    private fun openTranslations(event: MotionEvent) {
        if (event.x >= stripWidth) return
        val index = strip.indexAt(event.x + scrollX)
        val candidate = strip.candidates.getOrNull(index) ?: return
        val senses = candidate.senses
        if (senses.isEmpty() && !candidate.forgettable) return
        popupIndex = index
        repaint()
        Haptics.hold(context)
        val items = senses.map { sense ->
            ChoicePopup.Item(sense.text, listOfNotNull(sense.pos, sense.reading).joinToString(" "), sense.fresh)
        } + if (candidate.forgettable) listOf(ChoicePopup.Item(context.getString(R.string.candidate_forget), context.getString(R.string.candidate_forget_note))) else emptyList()
        forgetItem = if (candidate.forgettable) senses.size else -1
        forgetOnly = senses.isEmpty()
        // 只有「删除」时不预选，得滑上去才算，免得长按一松手就删了
        popup.show(items, initial = if (senses.isEmpty()) -1 else 0, fingerScreenX = event.rawX, bottom = 0f)
    }

    /** 气泡开着：横向移动只用来选项，抬手上屏；往下滑进键盘算取消。 */
    private fun onPopupTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                // 只有「删除」时手指得往上滑进气泡才算选中
                popup.track(event.rawX, cancelled = event.y > height + 24 * density || (forgetOnly && event.y > 0))

            MotionEvent.ACTION_UP -> {
                val index = popupIndex
                val forget = forgetItem
                closePopup()?.let { item ->
                    Haptics.tap(context)
                    if (item == forget) listener.onForget(index) else listener.onSelectTranslation(index, item)
                }
            }

            MotionEvent.ACTION_CANCEL -> closePopup()
        }
        return true
    }

    private fun closePopup(): Int? {
        val sense = if (popup.showing) popup.finish() else null
        // 气泡对着的不是第一个候选时，高亮要回到第一个
        val moved = popupIndex > 0
        popupIndex = -1
        if (moved) repaint()
        return sense
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(pendingRender)
        closePopup()
    }

    private fun maxScroll(): Int = (strip.contentWidth - stripWidth).toInt().coerceAtLeast(0)

    private companion object {
        const val NOTICE_MS = 5000L
    }
}
