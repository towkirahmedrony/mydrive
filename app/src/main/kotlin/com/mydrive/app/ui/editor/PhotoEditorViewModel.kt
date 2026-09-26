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
import com.mydrive.app.data.repository.SaveEditedPhotoResult
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
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
    val cropAspect: CropAspectPreset = CropAspectPreset.FREE,
    val sourceAspect: Float = 1f,
    val filterThumbs: Map<EditorFilter, Bitmap> = emptyMap(),
    val eyedropperEnabled: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isEdited: Boolean = false,
    val isLoading: Boolean = true,
    val isRendering: Boolean = false,
    val isSaving: Boolean = false,
    val loadFailed: Boolean = false,
    /** True while a cloud-only source is being prepared for editing. */
    val loadingFromCloud: Boolean = false,
    val loadErrorMessage: String? = null,
    val sampledColor: Long? = null,
    /** Set to the new MediaStore item id after a successful save. */
    val savedMediaId: String? = null,
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
    /** App-private copy downloaded for a cloud-only photo; deleted when the editor closes. */
    private var sourceTempFile: File? = null
    private var renderJob: Job? = null
    private var thumbJob: Job? = null
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
        val previous = _uiState.value.selectedTool
        _uiState.update {
            it.copy(
                selectedTool = tool,
                selectedTextId = if (tool == EditorTool.TEXT) it.selectedTextId else null,
                selectedStickerId = if (tool == EditorTool.STICKERS) it.selectedStickerId else null,
                eyedropperEnabled = if (tool == EditorTool.DRAW || tool == EditorTool.TEXT) it.eyedropperEnabled else false
            )
        }
        if ((previous == EditorTool.CROP) != (tool == EditorTool.CROP)) {
            requestRender()
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
        _uiState.update { it.copy(cropAspect = CropAspectPreset.FREE, eyedropperEnabled = false, sampledColor = null) }
        publishRecipe(rerender = true)
    }

    fun rotateLeft() = commit { it.withRotation(-90) }
    fun rotateRight() = commit { it.withRotation(90) }
    fun flipHorizontal() = commit { it.copy(flipHorizontal = !it.flipHorizontal) }
    fun flipVertical() = commit { it.copy(flipVertical = !it.flipVertical) }

    fun updateCrop(crop: NormalizedRect) = previewChange(rerender = false) { it.copy(crop = crop.coerced()) }

    fun updateDisplayCrop(displayCrop: NormalizedRect) {
        val recipe = store.current
        val sourceCrop = EditorCropMath.mapRectToSource(
            displayCrop.coerced(),
            recipe.rotationDegrees,
            recipe.flipHorizontal,
            recipe.flipVertical
        )
        updateCrop(sourceCrop)
    }

    fun setCropAspect(preset: CropAspectPreset) {
        _uiState.update { it.copy(cropAspect = preset) }
        if (preset == CropAspectPreset.FREE) return
        val recipe = store.current
        val rotation = ((recipe.rotationDegrees % 360) + 360) % 360
        val sourceAspect = _uiState.value.sourceAspect.coerceAtLeast(0.0001f)
        val displayAspect = if (rotation == 90 || rotation == 270) 1f / sourceAspect else sourceAspect
        val displayFitted = EditorCropMath.fitPreset(preset, displayAspect)
        val sourceCrop = EditorCropMath.mapRectToSource(
            displayFitted,
            recipe.rotationDegrees,
            recipe.flipHorizontal,
            recipe.flipVertical
        )
        commit(rerender = false) { it.copy(crop = sourceCrop) }
    }

    fun resetCrop() {
        _uiState.update { it.copy(cropAspect = CropAspectPreset.FREE) }
        commit(rerender = false) { it.copy(crop = NormalizedRect.Full) }
    }

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
        _uiState.update { it.copy(brushColor = EditorPalette.argb(color), eyedropperEnabled = false, sampledColor = null) }
    }

    fun setEyedropperEnabled(enabled: Boolean) {
        _uiState.update { it.copy(eyedropperEnabled = enabled) }
    }

    /**
     * Turns the picker on, or closes it (committing whatever color was sampled)
     * when it is already active.
     */
    fun toggleEyedropper() {
        if (_uiState.value.eyedropperEnabled) finishEyedropper() else setEyedropperEnabled(true)
    }

    /**
     * Samples the DISPLAYED preview at a normalized point. The preview bitmap is
     * exactly what the picker overlays, so the pixel under the finger is the
     * pixel read here regardless of the image's scale, crop, rotation or flips. It
     * applies the color live (for the loupe preview and the active tool) without
     * closing the picker; [finishEyedropper] closes it on release.
     */
    fun sampleDisplayedColor(x: Float, y: Float) {
        val preview = _uiState.value.preview ?: return
        if (preview.isRecycled) return
        val color = EditorPalette.sampleNormalized(preview.width, preview.height, x, y) { px, py ->
            preview.getPixel(px, py)
        }
        val state = _uiState.value
        if (state.selectedTool == EditorTool.TEXT) {
            val textId = state.selectedTextId
            if (textId != null) {
                previewChange(rerender = false) { recipe ->
                    recipe.copy(texts = recipe.texts.map { overlay ->
                        if (overlay.id == textId) overlay.copy(color = EditorPalette.argb(color)) else overlay
                    })
                }
            }
        } else {
            _uiState.update { it.copy(brushColor = EditorPalette.argb(color)) }
        }
        _uiState.update { it.copy(sampledColor = color) }
    }

    /**
     * Ends a picker session: commits the sampled color as one undo step, closes
     * the picker, and drops the transient loupe swatch. The chosen color itself
     * stays on the active Draw brush or Text overlay.
     */
    fun finishEyedropper() {
        if (_uiState.value.selectedTool == EditorTool.TEXT) {
            commitLiveChange()
        }
        _uiState.update { it.copy(eyedropperEnabled = false, sampledColor = null) }
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

    fun setTextColor(id: String, color: Long) {
        commit(rerender = false) { recipe ->
            recipe.copy(texts = recipe.texts.map { overlay ->
                if (overlay.id == id) overlay.copy(color = EditorPalette.argb(color)) else overlay
            })
        }
        _uiState.update { it.copy(eyedropperEnabled = false) }
    }

    fun removeText(id: String) {
        commit(rerender = false) { recipe ->
            recipe.copy(texts = recipe.texts.filterNot { it.id == id })
        }
        _uiState.update { state ->
            state.copy(selectedTextId = state.selectedTextId.takeUnless { it == id })
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

    fun removeSticker(id: String) {
        commit(rerender = false) { recipe ->
            recipe.copy(stickers = recipe.stickers.filterNot { it.id == id })
        }
        _uiState.update { state ->
            state.copy(selectedStickerId = state.selectedStickerId.takeUnless { it == id })
        }
    }

    /**
     * Renders the recipe at the editor's source resolution (crop applied, draw /
     * text / sticker overlays baked in, processed off the main thread) and saves a
     * NEW image into the device MediaStore. The original is never touched and no
     * `media_assets` row is created here — the existing MediaStore discovery and
     * backup flow picks the new item up on its own.
     */
    fun savePreview(overlay: EditorOverlayRenderContext = EditorOverlayRenderContext.Default) {
        val source = sourcePreview
        val current = item
        if (source == null || source.isRecycled) {
            Toast.makeText(context, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (current == null) {
            Toast.makeText(context, "This photo is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, saveMessage = null) }
        val recipe = store.current
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val base = EditorPreviewPipeline.renderSync(source, recipe, applyCrop = true)
                    val composed = if (recipe.strokes.isEmpty() && recipe.texts.isEmpty() && recipe.stickers.isEmpty()) {
                        base
                    } else {
                        EditorExportRenderer.renderOverlays(
                            context = context,
                            base = base,
                            recipe = recipe,
                            displayWidthPx = overlay.displayWidthPx,
                            density = overlay.density,
                            fontScale = overlay.fontScale
                        )
                    }
                    val result = repository.saveEditedPhoto(
                        context = context,
                        source = current,
                        bitmap = composed,
                        exifSourceUri = exifSourceUri(current)
                    )
                    if (composed !== base && composed !== source && !composed.isRecycled) composed.recycle()
                    if (base !== source && base !== composed && !base.isRecycled) base.recycle()
                    result
                }
            }.getOrNull()
            _uiState.update { state ->
                state.copy(
                    isSaving = false,
                    savedMediaId = (outcome as? SaveEditedPhotoResult.Saved)?.itemId,
                    saveMessage = when (outcome) {
                        is SaveEditedPhotoResult.Saved -> null
                        is SaveEditedPhotoResult.PermissionDenied ->
                            "Allow photo access, then try saving again."
                        is SaveEditedPhotoResult.Failed ->
                            "Couldn't save the edited photo. Please try again."
                        null -> "Couldn't save the edited photo. Please try again."
                    }
                )
            }
            if (outcome is SaveEditedPhotoResult.Saved) {
                Toast.makeText(context, "Saved to Photos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun consumeSaveMessage() {
        _uiState.update { it.copy(saveMessage = null) }
    }

    fun consumeSaved() {
        _uiState.update { it.copy(savedMediaId = null) }
    }

    /**
     * The picture the EXIF block should be copied from: the local MediaStore
     * original, or the app-private cloud copy the editor already downloaded.
     */
    private fun exifSourceUri(current: MediaItem): String? {
        if (current.originLocal && current.uri.isNotBlank()) return current.uri
        return sourceTempFile?.let { android.net.Uri.fromFile(it).toString() }
    }

    private fun loadSource() {
        val target = item
        if (target == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadFailed = true,
                    loadErrorMessage = "This photo could not be found."
                )
            }
            return
        }
        loadSourceFor(target)
    }

    /** Retries a failed source load, including a failed cloud download. */
    fun retryLoad() {
        val target = item ?: return
        if (_uiState.value.isLoading) return
        loadSourceFor(target)
    }

    private fun loadSourceFor(target: MediaItem) {
        sourcePreview?.takeIf { !it.isRecycled }?.recycle()
        sourcePreview = null
        _uiState.update {
            it.copy(
                isLoading = true,
                loadFailed = false,
                loadErrorMessage = null,
                // A cloud-only item is downloaded before it can be edited. A local
                // item is opened instantly, so the download hint is only shown
                // when the source genuinely has to come from the cloud.
                loadingFromCloud = !target.originLocal
            )
        }
        viewModelScope.launch {
            when (val resolved = EditorSourceResolver.resolve(context, target)) {
                is EditorSourceResolver.Result.Local -> openResolvedSource(resolved.uri)
                is EditorSourceResolver.Result.Downloaded -> {
                    sourceTempFile = resolved.file
                    _uiState.update { it.copy(loadingFromCloud = true) }
                    openResolvedSource(android.net.Uri.fromFile(resolved.file).toString())
                }
                is EditorSourceResolver.Result.Failed -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadingFromCloud = false,
                            loadFailed = true,
                            loadErrorMessage = resolved.message
                        )
                    }
                }
            }
        }
    }

    private suspend fun openResolvedSource(uri: String) {
        val bitmap = EditorImageLoader.loadPreview(context, uri)
        if (bitmap == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadingFromCloud = false,
                    loadFailed = true,
                    loadErrorMessage = "This photo could not be opened for editing."
                )
            }
            return
        }
        sourcePreview = bitmap
        _uiState.update {
            it.copy(
                isLoading = false,
                loadingFromCloud = false,
                loadFailed = false,
                loadErrorMessage = null,
                sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
            )
        }
        requestRender()
        generateFilterThumbs(bitmap)
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

    private fun generateFilterThumbs(source: Bitmap) {
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            val generated = linkedMapOf<EditorFilter, Bitmap>()
            try {
                EditorFilter.entries.forEach { filter ->
                    ensureActive()
                    generated[filter] = EditorPreviewPipeline.renderFilterThumb(source, filter)
                }
                _uiState.update { it.copy(filterThumbs = generated) }
            } catch (cancelled: CancellationException) {
                generated.values.forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                throw cancelled
            }
        }
    }

    private fun requestRender() {
        val source = sourcePreview ?: return
        val recipe = store.current
        val applyCrop = _uiState.value.selectedTool != EditorTool.CROP
        renderJob?.cancel()
        _uiState.update { it.copy(isRendering = true) }
        renderJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                EditorPreviewPipeline.renderSync(source, recipe, applyCrop = applyCrop)
            }
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
        thumbJob?.cancel()
        _uiState.value.filterThumbs.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        val preview = _uiState.value.preview
        if (preview != null && preview !== sourcePreview && !preview.isRecycled) {
            preview.recycle()
        }
        sourcePreview?.takeIf { !it.isRecycled }?.recycle()
        sourcePreview = null
        sourceTempFile?.let { file -> runCatching { file.delete() } }
        sourceTempFile = null
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
