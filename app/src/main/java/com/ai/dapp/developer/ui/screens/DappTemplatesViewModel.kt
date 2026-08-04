package com.ai.dapp.developer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.dapp.developer.dapp.DappCategory
import com.ai.dapp.developer.dapp.DappTemplateManager
import com.ai.dapp.developer.dapp.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DappTemplatesViewModel @Inject constructor(
    private val templateManager: DappTemplateManager
) : ViewModel() {
    
    val templates = templateManager.templates
    val isGenerating = templateManager.isGenerating
    
    private val _selectedPlatform = MutableStateFlow<Platform?>(null)
    val selectedPlatform: StateFlow<Platform?> = _selectedPlatform.asStateFlow()
    
    init {
        filterTemplates()
    }
    
    fun selectPlatform(platform: Platform?) {
        _selectedPlatform.value = platform
        filterTemplates()
    }
    
    private fun filterTemplates() {
        val platform = _selectedPlatform.value
        val filtered = if (platform == null) {
            templateManager.templates.value
        } else {
            templateManager.getTemplatesByPlatform(platform)
        }
        // Update templates with filtered list
        // Note: In real implementation, you'd have a separate filtered list
    }
    
    fun generateCustomDapp(
        description: String,
        platform: Platform,
        category: DappCategory
    ) {
        viewModelScope.launch {
            templateManager.generateCustomDapp(description, platform, category)
        }
    }
}
