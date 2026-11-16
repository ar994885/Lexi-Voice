package com.example.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import java.util.Locale

data class SelectableExportChapter(
    val index: Int,
    val originalTitle: String,
    var exportTitle: String,
    val href: String,
    val wordCount: Int,
    val estimatedMinutes: Int,
    val plainTextOverride: String? = null,
    var isSelected: Boolean = true
)

class ExportChapterAdapter(
    private val onTapChapter: (position: Int) -> Unit,
    private val onEditChapterName: (position: Int) -> Unit
) : RecyclerView.Adapter<ExportChapterAdapter.ChapterViewHolder>() {

    val items = mutableListOf<SelectableExportChapter>()

    fun submitChapters(newItems: List<SelectableExportChapter>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_export_chapter, parent, false)
        return ChapterViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position, onTapChapter, onEditChapterName)
    }

    override fun getItemCount(): Int = items.size

    class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.row_root)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_chapter)
        private val numberTv: TextView = itemView.findViewById(R.id.tv_chapter_number)
        private val titleTv: TextView = itemView.findViewById(R.id.tv_chapter_title)
        private val metaTv: TextView = itemView.findViewById(R.id.tv_chapter_meta)
        private val editBtn: ImageButton = itemView.findViewById(R.id.btn_edit_chapter_name)

        fun bind(
            item: SelectableExportChapter,
            position: Int,
            onTap: (Int) -> Unit,
            onEdit: (Int) -> Unit
        ) {
            root.isSelected = item.isSelected
            checkBox.isChecked = item.isSelected
            numberTv.text = String.format(Locale.US, "%03d", position + 1)
            titleTv.text = item.exportTitle
            metaTv.text = "${item.wordCount} words • ~${item.estimatedMinutes} min"
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onTap(pos)
            }
            editBtn.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEdit(pos)
            }
        }
    }
}

/**
 * Custom RecyclerView.OnItemTouchListener implementing smooth long-press + drag-to-select
 * across chapters, including automatic edge scrolling when dragging near top or bottom.
 */
class DragSelectTouchListener(
    context: Context,
    private val onRangeSelectionChanged: (startPos: Int, endPos: Int, targetSelected: Boolean) -> Unit,
    private val getCurrentSelectedState: (position: Int) -> Boolean
) : RecyclerView.OnItemTouchListener {

    private var isDragActive = false
    private var startPosition = RecyclerView.NO_POSITION
    private var lastPosition = RecyclerView.NO_POSITION
    private var targetSelectionState = true
    private var lastTouchY = 0f
    private var attachedRecyclerView: RecyclerView? = null

    private val scrollHandler = Handler(Looper.getMainLooper())
    private var scrollDelta = 0
    private val edgeZonePx = (64 * context.resources.displayMetrics.density).toInt()
    private val maxScrollStepPx = (16 * context.resources.displayMetrics.density).toInt()

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val rv = attachedRecyclerView ?: return
            if (!isDragActive || scrollDelta == 0) return
            rv.scrollBy(0, scrollDelta)
            updateSelectionAtY(rv, lastTouchY)
            scrollHandler.postDelayed(this, 16L)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val rv = attachedRecyclerView ?: return
                val child = rv.findChildViewUnder(e.x, e.y) ?: return
                val pos = rv.getChildAdapterPosition(child)
                if (pos != RecyclerView.NO_POSITION) {
                    isDragActive = true
                    startPosition = pos
                    lastPosition = pos
                    targetSelectionState = !getCurrentSelectedState(pos)
                    onRangeSelectionChanged(pos, pos, targetSelectionState)
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
    )

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        attachedRecyclerView = rv
        gestureDetector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            stopDrag()
        }
        return isDragActive
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        attachedRecyclerView = rv
        if (!isDragActive) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastTouchY = e.y
                updateSelectionAtY(rv, e.y)
                handleEdgeAutoScroll(rv, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDrag()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) stopDrag()
    }

    private fun updateSelectionAtY(rv: RecyclerView, y: Float) {
        val clampedY = y.coerceIn(1f, (rv.height - 1).coerceAtLeast(1).toFloat())
        val child = rv.findChildViewUnder(rv.width / 2f, clampedY) ?: return
        val pos = rv.getChildAdapterPosition(child)
        if (pos != RecyclerView.NO_POSITION && pos != lastPosition) {
            lastPosition = pos
            onRangeSelectionChanged(startPosition, pos, targetSelectionState)
        }
    }

    private fun handleEdgeAutoScroll(rv: RecyclerView, y: Float) {
        val newDelta = when {
            y < edgeZonePx -> -maxScrollStepPx
            y > rv.height - edgeZonePx -> maxScrollStepPx
            else -> 0
        }
        if (newDelta != 0 && scrollDelta == 0) {
            scrollDelta = newDelta
            scrollHandler.post(autoScrollRunnable)
        } else {
            scrollDelta = newDelta
            if (scrollDelta == 0) {
                scrollHandler.removeCallbacks(autoScrollRunnable)
            }
        }
    }

    private fun stopDrag() {
        isDragActive = false
        startPosition = RecyclerView.NO_POSITION
        lastPosition = RecyclerView.NO_POSITION
        scrollDelta = 0
        scrollHandler.removeCallbacks(autoScrollRunnable)
    }
}
