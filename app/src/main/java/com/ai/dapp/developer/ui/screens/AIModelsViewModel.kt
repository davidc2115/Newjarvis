package com.ai.dapp.developer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.dapp.developer.ai.AIModel
import com.ai.dapp.developer.ai.AIModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AIModelsViewModel @Inject constructor(
    private val modelManager: AIModelManager
) : ViewModel() {
    
    val availableModels = modelManager.availableModels
    val currentModel = modelManager.currentModel
    val isGenerating = modelManager.isGenerating
    
    private val _isGeneratingText = MutableStateFlow(false)
    val isGeneratingText: StateFlow<Boolean> = _isGeneratingText.asStateFlow()
    
    private val _generatedText = MutableStateFlow("")
    val generatedText: StateFlow<String> = _generatedText.asStateFlow()
    
    fun loadModel(model: AIModel) {
        viewModelScope.launch {
            modelManager.loadModel(model)
        }
    }
    
    fun unloadModel() {
        modelManager.unloadModel()
    }
    
    fun generateText(prompt: String) {
        viewModelScope.launch {
            _isGeneratingText.value = true
            val result = modelManager.generateText(prompt)
            result.onSuccess { text ->
                _generatedText.value = text
            }.onFailure { error ->
                _generatedText.value = "Error: ${error.message}"
            }
            _isGeneratingText.value = false
        }
    }
}
