package com.mydrive.app.ui.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.MediaRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoEditorUiState(
    val mediaId: String,
    val filename: String,
    val recipe: PhotoEditorRecipe,
    val selectedTool: EditorTool = EditorTool.CROP,
    val selectedTextId: String? = null,
    val selectedStickerId: String? = null,
    val brushSize: Float = 0.018f,
    val brushColor: Long = 0xFFFFFFFF,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isEdited: Boolean = false,
    val isLoading: Boolean = true,
    val isRendering: Boolean = false,
    val isSaving: Boolean = false,
    val loadFailed: Boolean = false,
    val preview: Bitmap? = null,
    val saveMessage: String? = null
)

class PhotoEditorViewModel(
    private val repository: MediaRepository,
    private val mediaId: String,
    private val appContext: Application
) : ViewModel() {

    private val context: Context get() = appContext.applicationContext
    private val item: MediaItem? = repository.mediaById(mediaId)?.takeIf { it.type == MediaType.PHOTO }
    private val store = PhotoEditorStore(
        PhotoEditorRecipe(
            sourceUri = item?.originalUri?.ifBlank { item.uri }.orEmpty(),
            mediaId = mediaId
        )
    )
    private var sourcePreview: Bitmap? = null
    private var renderJob: Job? = null
    private var drawingStroke: EditorDrawStroke? = null
    private var overlayBefore: PhotoEditorRecipe? = null

    private val _uiState = MutableStateFlow(
        PhotoEditorUiState(
            mediaId = mediaId,
            filename = item?.filename.orEmpty().ifBlank { "Edit" },
            recipe = store.current
        )
    )
    val uiState: StateFlow<PhotoEditorUiState> = _uiState

    init {
        loadSource()
    }

    fun selectTool(tool: EditorTool) {
        commitLiveChange()
        _uiState.update {
            it.copy(
                selectedTool = tool,
                selectedTextId = if (tool == EditorTool.TEXT) it.selectedTextId else null,
                selectedStickerId = if (tool == EditorTool.STICKERS) it.selectedStickerId else null
            )
        }
    }

    fun undo() {
        overlayBefore = null
        store.undo()
        publishRecipe(rerender = true)
    }

    fun redo() {
        overlayBefore = null
        store.redo()
        publishRecipe(rerender = true)
    }

    fun reset() {
        overlayBefore = null
        store.reset()
        publishRecipe(rerender = true)
    }

    fun rotateLeft() = commit { it.withRotation(-90) }
    fun rotateRight() = commit { it.withRotation(90) }
    fun flipHorizontal() = commit { it.copy(flipHorizontal = !it.flipHorizontal) }
    fun flipVertical() = commit { it.copy(flipVertical = !it.flipVertical) }

    fun updateCrop(crop: NormalizedRect) = previewChange { it.copy(crop = crop.coerced()) }

    fun resetCrop() = commit { it.copy(crop = NormalizedRect.Full) }

    fun updateAdjustment(transform: (EditorAdjustments) -> EditorAdjustments) {
        previewChange { recipe -> recipe.copy(adjustments = transform(recipe.adjustments)) }
    }

    fun commitLiveChange() {
        val before = overlayBefore ?: return
        overlayBefore = null
        store.commitFrom(before)
        publishRecipe(rerender = false)
    }

    fun resetAdjustments() = commit { it.copy(adjustments = EditorAdjustments()) }

    fun selectFilter(filter: EditorFilter) = commit { it.copy(filter = filter) }

    fun setBrushSize(size: Float) {
        _uiState.update { it.copy(brushSize = size.coerceIn(0.006f, 0.08f)) }
    }

    fun setBrushColor(color: Long) {
        _uiState.update { it.copy(brushColor = color) }
    }

    fun beginStroke(x: Float, y: Float) {
        drawingStroke = EditorDrawStroke(
            id = UUID.randomUUID().toString(),
            points = listOf(EditorDrawPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))),
            color = _uiState.value.brushColor,
            width = _uiState.value.brushSize
        )
        overlayBefore = store.current
        store.replace(store.current.copy(strokes = store.current.strokes + drawingStroke!!))
        publishRecipe(rerender = false)
    }

    fun appendStroke(x: Float, y: Float) {
        val currentStroke = drawingStroke ?: return
        val next = currentStroke.copy(
            points = currentStroke.points + EditorDrawPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        )
        drawingStroke = next
        store.replace(store.current.copy(strokes = store.current.strokes.dropLast(1) + next))
        publishRecipe(rerender = false)
    }

    fun endStroke() {
        val before = overlayBefore
        drawingStroke = null
        overlayBefore = null
        if (before != null) {
            store.commitFrom(before)
        }
        publishRecipe(rerender = false)
    }

    fun undoStroke() {
        if (store.current.strokes.isEmpty()) return
        commit(rerender = false) { it.copy(strokes = it.strokes.dropLast(1)) }
    }

    fun clearStrokes() {
        if (store.current.strokes.isEmpty()) return
        commit(rerender = false) { it.copy(strokes = emptyList()) }
    }

    fun addText() {
        val overlay = EditorTextOverlay(
            id = UUID.randomUUID().toString(),
            text = "Text",
            x = 0.5f,
            y = 0.5f
        )
        commit(rerender = false) { it.copy(texts = it.texts + overlay) }
        _uiState.update { it.copy(selectedTextId = overlay.id, selectedTool = EditorTool.TEXT) }
    }

    fun updateText(id: String, text: String) {
        previewChange(rerender = false) { recipe ->
            recipe.copy(texts = recipe.texts.map { overlay ->
                if (overlay.id == id) overlay.copy(text = text) else overlay
            })
        }
    }

    fun selectText(id: String?) {
        _uiState.update { it.copy(selectedTextId = id, selectedStickerId = null) }
    }

    fun beginOverlayGesture() {
        if (overlayBefore == null) overlayBefore = store.current
    }

    fun moveText(id: String, x: Float, y: Float) {
        store.replace(store.current.copy(texts = store.current.texts.map { overlay ->
            if (overlay.id == id) overlay.copy(x = x.coerceIn(0.05f, 0.95f), y = y.coerceIn(0.05f, 0.95f)) else overlay
        }))
        publishRecipe(rerender = false)
    }

    fun transformText(id: String, scale: Float, rotation: Float) {
        store.replace(store.current.copy(texts = store.current.texts.map { overlay ->
            if (overlay.id == id) overlay.copy(scale = scale.coerceIn(0.4f, 4f), rotation = rotation) else overlay
        }))
        publishRecipe(rerender = false)
    }

    fun endOverlayGesture() {
        val before = overlayBefore ?: return
        overlayBefore = null
        store.commitFrom(before)
        publishRecipe(rerender = false)
    }

    fun addSticker(kind: EditorStickerKind) {
        val overlay = EditorStickerOverlay(
            id = UUID.randomUUID().toString(),
            stickerId = kind.id,
            x = 0.5f,
            y = 0.5f
        )
        commit(rerender = false) { it.copy(stickers = it.stickers + overlay) }
        _uiState.update { it.copy(selectedStickerId = overlay.id, selectedTool = EditorTool.STICKERS) }
    }

    fun selectSticker(id: String?) {
        _uiState.update { it.copy(selectedStickerId = id, selectedTextId = null) }
    }

    fun moveSticker(id: String, x: Float, y: Float) {
        store.replace(store.current.copy(stickers = store.current.stickers.map { overlay ->
            if (overlay.id == id) overlay.copy(x = x.coerceIn(0.05f, 0.95f), y = y.coerceIn(0.05f, 0.95f)) else overlay
        }))
        publishRecipe(rerender = false)
    }

    fun transformSticker(id: String, scale: Float, rotation: Float) {
        store.replace(store.current.copy(stickers = store.current.stickers.map { overlay ->
            if (overlay.id == id) overlay.copy(scale = scale.coerceIn(0.4f, 4f), rotation = rotation) else overlay
        }))
        publishRecipe(rerender = false)
    }

    fun savePreview() {
        val preview = _uiState.value.preview
        if (preview == null) {
            Toast.makeText(context, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "editor-previews").apply { mkdirs() }
                    val file = File(dir, "edit-${mediaId}-${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { stream ->
                        preview.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    }
                    file.absolutePath
                }.getOrNull()
            }
            _uiState.update { state ->
                state.copy(
                    isSaving = false,
                    saveMessage = if (saved != null) {
                        "Saved a temporary editor preview. Full export comes later."
                    } else {
                        "Could not write the temporary preview."
                    }
                )
            }
        }
    }

    fun consumeSaveMessage() {
        _uiState.update { it.copy(saveMessage = null) }
    }

    private fun loadSource() {
        val uri = store.initial.sourceUri
        if (uri.isBlank()) {
            _uiState.update { it.copy(isLoading = false, loadFailed = true) }
            return
        }
        viewModelScope.launch {
            val bitmap = EditorImageLoader.loadPreview(context, uri)
            sourcePreview = bitmap
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadFailed = bitmap == null
                )
            }
            if (bitmap != null) {
                requestRender()
            }
        }
    }

    private fun previewChange(
        rerender: Boolean = true,
        transform: (PhotoEditorRecipe) -> PhotoEditorRecipe
    ) {
        if (overlayBefore == null) overlayBefore = store.current
        store.replace(transform(store.current))
        publishRecipe(rerender = rerender)
    }

    private fun commit(
        rerender: Boolean = true,
        transform: (PhotoEditorRecipe) -> PhotoEditorRecipe
    ) {
        overlayBefore = null
        val next = transform(store.current)
        store.apply(next)
        publishRecipe(rerender = rerender)
    }

    private fun publishRecipe(rerender: Boolean) {
        _uiState.update {
            it.copy(
                recipe = store.current,
                canUndo = store.canUndo,
                canRedo = store.canRedo,
                isEdited = store.isEdited
            )
        }
        if (rerender) requestRender()
    }

    private fun requestRender() {
        val source = sourcePreview ?: return
        val recipe = store.current
        renderJob?.cancel()
        _uiState.update { it.copy(isRendering = true) }
        renderJob = viewModelScope.launch {
            val rendered = EditorPreviewPipeline.render(source, recipe)
            ensureActive()
            val previous = _uiState.value.preview
            _uiState.update { it.copy(preview = rendered, isRendering = false) }
            if (previous != null && previous !== source && previous !== rendered && !previous.isRecycled) {
                previous.recycle()
            }
        }
    }

    override fun onCleared() {
        renderJob?.cancel()
        val preview = _uiState.value.preview
        if (preview != null && preview !== sourcePreview && !preview.isRecycled) {
            preview.recycle()
        }
        sourcePreview?.takeIf { !it.isRecycled }?.recycle()
        sourcePreview = null
        super.onCleared()
    }

    companion object {
        fun factory(repository: MediaRepository, mediaId: String, app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PhotoEditorViewModel(repository, mediaId, app) as T
            }
    }
}
