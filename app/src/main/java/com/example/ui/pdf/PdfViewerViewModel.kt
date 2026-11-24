package com.example.ui.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PdfViewerUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val currentPage: Int = 0,
    val pageCount: Int = 0,
    val pageBitmap: Bitmap? = null,
    val errorMessage: String? = null
)

class PdfViewerViewModel(
    application: Application,
    private val fileId: String
) : AndroidViewModel(application) {

    private val repository = WorkspaceRepository.getInstance(application)
    private val renderMutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    // Keep at most 3 rendered pages cached to minimize RAM usage on low-memory devices
    private val pageCache = object : LruCache<Int, Bitmap>(3) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        openPdf()
    }

    private fun openPdf() {
        viewModelScope.launch {
            val meta = repository.getFileMeta(fileId)
            if (meta == null) {
                _uiState.value = PdfViewerUiState(isLoading = false, errorMessage = "PDF record not found.")
                return@launch
            }
            val file = repository.getLocalFile(meta.localFilename)
            if (!file.exists()) {
                _uiState.value = PdfViewerUiState(
                    isLoading = false,
                    title = meta.title,
                    errorMessage = "PDF file is missing from local storage."
                )
                return@launch
            }

            val openResult = withContext(Dispatchers.IO) {
                runCatching {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    pfd to renderer
                }
            }

            openResult.onSuccess { (pfd, renderer) ->
                fileDescriptor = pfd
                pdfRenderer = renderer
                val count = renderer.pageCount
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        title = meta.title,
                        pageCount = count,
                        currentPage = 0,
                        errorMessage = if (count == 0) "This PDF has no pages." else null
                    )
                }
                if (count > 0) {
                    renderPage(0)
                }
            }.onFailure { err ->
                _uiState.value = PdfViewerUiState(
                    isLoading = false,
                    title = meta.title,
                    errorMessage = "Unable to render PDF: ${err.localizedMessage ?: "Invalid format"}"
                )
            }
        }
    }

    fun goToPage(pageIndex: Int) {
        val count = _uiState.value.pageCount
        if (count <= 0) return
        val clamped = pageIndex.coerceIn(0, count - 1)
        if (clamped == _uiState.value.currentPage && _uiState.value.pageBitmap != null) return
        _uiState.update { it.copy(currentPage = clamped) }
        renderPage(clamped)
    }

    fun nextPage() = goToPage(_uiState.value.currentPage + 1)
    fun previousPage() = goToPage(_uiState.value.currentPage - 1)

    private fun renderPage(pageIndex: Int) {
        viewModelScope.launch {
            val cached = pageCache.get(pageIndex)
            if (cached != null && !cached.isRecycled) {
                _uiState.update { it.copy(pageBitmap = cached) }
                return@launch
            }

            val rendered = withContext(Dispatchers.IO) {
                renderMutex.withLock {
                    val renderer = pdfRenderer ?: return@withLock null
                    if (pageIndex !in 0 until renderer.pageCount) return@withLock null
                    renderer.openPage(pageIndex).use { page ->
                        val maxTargetWidth = 1440
                        val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                        val width = maxTargetWidth.coerceAtMost(page.width * 2).coerceAtLeast(600)
                        val height = (width * aspect).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // Fill with white paper background before rendering transparent PDF layers
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }

            if (rendered != null) {
                pageCache.put(pageIndex, rendered)
                if (_uiState.value.currentPage == pageIndex) {
                    _uiState.update { it.copy(pageBitmap = rendered) }
                }
            }
        }
    }

    fun exportPdfUri(destUri: Uri) {
        viewModelScope.launch {
            repository.exportToUri(fileId, destUri)
                .onSuccess { _toastMessage.emit("PDF exported") }
                .onFailure { _toastMessage.emit("Export failed") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Release native PdfRenderer and cached bitmaps promptly
        _uiState.update { it.copy(pageBitmap = null) }
        pageCache.evictAll()
        runCatching { pdfRenderer?.close() }
        runCatching { fileDescriptor?.close() }
        pdfRenderer = null
        fileDescriptor = null
    }

    class Factory(
        private val application: Application,
        private val fileId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PdfViewerViewModel(application, fileId) as T
        }
    }
}
