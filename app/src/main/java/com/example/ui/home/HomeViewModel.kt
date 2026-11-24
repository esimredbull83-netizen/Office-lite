package com.example.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfficeFileEntity
import com.example.data.OfficeFileType
import com.example.data.WorkspaceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FileFilter(val label: String) {
    ALL("All"),
    DOC("Docs"),
    SHEET("Sheets"),
    PDF("PDFs")
}

sealed interface HomeEvent {
    data class OpenFile(val file: OfficeFileEntity) : HomeEvent
    data class ShowMessage(val message: String) : HomeEvent
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeFilter = MutableStateFlow(FileFilter.ALL)
    val activeFilter: StateFlow<FileFilter> = _activeFilter.asStateFlow()

    val isDarkTheme: StateFlow<Boolean> = repository.isDarkTheme

    private val _events = MutableSharedFlow<HomeEvent>()
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    val allFilesCount: StateFlow<Int> = repository.recentFiles
        .combine(_searchQuery) { list, _ -> list.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val filteredFiles: StateFlow<List<OfficeFileEntity>> = combine(
        repository.recentFiles,
        _searchQuery,
        _activeFilter
    ) { files, query, filter ->
        val trimmed = query.trim()
        files.filter { file ->
            val matchesFilter = when (filter) {
                FileFilter.ALL -> true
                FileFilter.DOC -> file.fileType == OfficeFileType.DOC
                FileFilter.SHEET -> file.fileType == OfficeFileType.SHEET
                FileFilter.PDF -> file.fileType == OfficeFileType.PDF
            }
            val matchesQuery = trimmed.isEmpty() ||
                file.title.contains(trimmed, ignoreCase = true) ||
                file.previewSnippet.contains(trimmed, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectFilter(filter: FileFilter) {
        _activeFilter.value = filter
    }

    fun toggleTheme() {
        repository.toggleTheme()
    }

    fun createNewDoc() {
        viewModelScope.launch {
            val file = repository.createNewDoc()
            _events.emit(HomeEvent.OpenFile(file))
        }
    }

    fun createNewSheet() {
        viewModelScope.launch {
            val file = repository.createNewSheet()
            _events.emit(HomeEvent.OpenFile(file))
        }
    }

    fun importLocalUri(uri: Uri) {
        viewModelScope.launch {
            repository.importFromUri(uri)
                .onSuccess { entity ->
                    _events.emit(HomeEvent.OpenFile(entity))
                }
                .onFailure { err ->
                    _events.emit(HomeEvent.ShowMessage(err.message ?: "Could not open file"))
                }
        }
    }

    fun exportFile(id: String, destUri: Uri) {
        viewModelScope.launch {
            repository.exportToUri(id, destUri)
                .onSuccess { _events.emit(HomeEvent.ShowMessage("File exported")) }
                .onFailure { _events.emit(HomeEvent.ShowMessage("Export failed")) }
        }
    }

    fun renameFile(id: String, newTitle: String) {
        viewModelScope.launch {
            repository.renameFile(id, newTitle)
        }
    }

    fun deleteFile(id: String) {
        viewModelScope.launch {
            repository.deleteFile(id)
            _events.emit(HomeEvent.ShowMessage("File removed"))
        }
    }
}
