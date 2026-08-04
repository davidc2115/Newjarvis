package com.ai.dapp.developer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.dapp.developer.github.GitHubAuthState
import com.ai.dapp.developer.github.GitHubManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GitHubViewModel @Inject constructor(
    private val githubManager: GitHubManager
) : ViewModel() {
    
    val authState: StateFlow<GitHubAuthState> = githubManager.authState
    val repositories = githubManager.repositories
    val isOperationInProgress = githubManager.isOperationInProgress
    
    fun authenticate(token: String) {
        viewModelScope.launch {
            githubManager.authenticate(token)
        }
    }
    
    fun loadRepositories() {
        viewModelScope.launch {
            githubManager.loadRepositories()
        }
    }
    
    fun cloneRepository(repo: com.ai.dapp.developer.github.GitHubRepository) {
        viewModelScope.launch {
            githubManager.cloneRepository(repo)
        }
    }
    
    fun logout() {
        githubManager.logout()
    }
}
