package com.ai.dapp.developer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.dapp.developer.dapp.DappTemplate
import com.ai.dapp.developer.dapp.DappTemplateManager
import com.ai.dapp.developer.dapp.TemplateFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DappEditorViewModel @Inject constructor(
    private val templateManager: DappTemplateManager
) : ViewModel() {
    
    private val _template = MutableStateFlow<DappTemplate?>(null)
    val template: StateFlow<DappTemplate?> = _template.asStateFlow()
    
    private val _selectedFile = MutableStateFlow<TemplateFile?>(null)
    val selectedFile: StateFlow<TemplateFile?> = _selectedFile.asStateFlow()
    
    val isEnhancing = templateManager.isGenerating
    
    fun loadTemplate(templateId: String) {
        viewModelScope.launch {
            val template = templateManager.templates.value.find { it.id == templateId }
            _template.value = template
        }
    }
    
    fun selectFile(file: TemplateFile) {
        _selectedFile.value = file
    }
    
    fun updateFileContent(filePath: String, content: String) {
        _template.value?.let { currentTemplate ->
            val updatedFiles = currentTemplate.files.map { file ->
                if (file.path == filePath) {
                    file.copy(content = content)
                } else {
                    file
                }
            }
            _template.value = currentTemplate.copy(files = updatedFiles)
        }
    }
    
    fun enhanceDappCode(enhancements: List<String>) {
        viewModelScope.launch {
            _template.value?.let { template ->
                val result = templateManager.enhanceDappCode(template, enhancements)
                result.onSuccess { enhancedTemplate ->
                    _template.value = enhancedTemplate
                }
            }
        }
    }
}
