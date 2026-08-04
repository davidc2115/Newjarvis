package com.ai.dapp.developer.github

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import java.io.File

data class GitHubRepository(
    val name: String,
    val owner: String,
    val url: String,
    val description: String?,
    val language: String?,
    val isPrivate: Boolean
)

data class GitHubUser(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val email: String?
)

sealed class GitHubAuthState {
    object NotAuthenticated : GitHubAuthState()
    object Authenticating : GitHubAuthState()
    data class Authenticated(val user: GitHubUser) : GitHubAuthState()
    data class Error(val message: String) : GitHubAuthState()
}

class GitHubManager(private val context: Context) {
    
    private var githubClient: GitHub? = null
    private val _authState = MutableStateFlow<GitHubAuthState>(GitHubAuthState.NotAuthenticated)
    val authState: StateFlow<GitHubAuthState> = _authState.asStateFlow()
    
    private val _repositories = MutableStateFlow<List<GitHubRepository>>(emptyList())
    val repositories: StateFlow<List<GitHubRepository>> = _repositories.asStateFlow()
    
    private val _isOperationInProgress = MutableStateFlow(false)
    val isOperationInProgress: StateFlow<Boolean> = _isOperationInProgress.asStateFlow()
    
    private val workDirectory: File
        get() = File(context.filesDir, "github_work").apply { mkdirs() }
    
    suspend fun authenticate(token: String): Result<GitHubUser> {
        return try {
            _authState.value = GitHubAuthState.Authenticating
            _isOperationInProgress.value = true
            
            val github = GitHubBuilder().withOAuthToken(token).build()
            this.githubClient = github
            
            val mySelf = github.myself
            val user = GitHubUser(
                login = mySelf.login,
                name = mySelf.name,
                avatarUrl = mySelf.avatarUrl,
                email = mySelf.email
            )
            
            _authState.value = GitHubAuthState.Authenticated(user)
            loadRepositories()
            
            Result.success(user)
        } catch (e: Exception) {
            _authState.value = GitHubAuthState.Error(e.message ?: "Authentication failed")
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun loadRepositories(): Result<List<GitHubRepository>> {
        return try {
            _isOperationInProgress.value = true
            
            val github = githubClient ?: return Result.failure(Exception("Not authenticated"))
            val repos = github.myself.listRepositories().toList()
            
            val githubRepos = repos.map { repo ->
                GitHubRepository(
                    name = repo.name,
                    owner = repo.owner.login,
                    url = repo.htmlUrl.toString(),
                    description = repo.description,
                    language = repo.language,
                    isPrivate = repo.private
                )
            }
            
            _repositories.value = githubRepos
            Result.success(githubRepos)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun cloneRepository(repo: GitHubRepository): Result<File> {
        return try {
            _isOperationInProgress.value = true
            
            val cloneDir = File(workDirectory, repo.name)
            
            // Clone using JGit
            Git.cloneRepository()
                .setURI(repo.url)
                .setDirectory(cloneDir)
                .call()
            
            Result.success(cloneDir)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun commitChanges(
        repoPath: String,
        message: String,
        files: List<String> = emptyList()
    ): Result<String> {
        return try {
            _isOperationInProgress.value = true
            
            val repoDir = File(repoPath)
            val repository = FileRepositoryBuilder()
                .setGitDir(File(repoDir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build()
            
            val git = Git(repository)
            
            // Add files
            if (files.isEmpty()) {
                git.add().addFilepattern(".").call()
            } else {
                files.forEach { file ->
                    git.add().addFilepattern(file).call()
                }
            }
            
            // Commit
            val commit = git.commit()
                .setMessage(message)
                .call()
            
            git.close()
            
            Result.success(commit.name)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun pushChanges(repoPath: String, branch: String = "main"): Result<Unit> {
        return try {
            _isOperationInProgress.value = true
            
            val repoDir = File(repoPath)
            val repository = FileRepositoryBuilder()
                .setGitDir(File(repoDir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build()
            
            val git = Git(repository)
            
            val github = githubClient ?: return Result.failure(Exception("Not authenticated"))
            // Note: In real implementation, you'd need to handle credentials for push
            
            git.push()
                .setRemote("origin")
                .setRefSpecs(org.eclipse.jgit.api.PushCommand().refSpecs)
                .call()
            
            git.close()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun pullChanges(repoPath: String, branch: String = "main"): Result<Unit> {
        return try {
            _isOperationInProgress.value = true
            
            val repoDir = File(repoPath)
            val repository = FileRepositoryBuilder()
                .setGitDir(File(repoDir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build()
            
            val git = Git(repository)
            
            git.pull()
                .setRemote("origin")
                .setRemoteBranchName(branch)
                .call()
            
            git.close()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun createBranch(repoPath: String, branchName: String): Result<String> {
        return try {
            _isOperationInProgress.value = true
            
            val repoDir = File(repoPath)
            val repository = FileRepositoryBuilder()
                .setGitDir(File(repoDir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build()
            
            val git = Git(repository)
            
            git.branchCreate()
                .setName(branchName)
                .call()
            
            git.close()
            
            Result.success(branchName)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun createRepository(
        name: String,
        description: String = "",
        isPrivate: Boolean = false
    ): Result<GitHubRepository> {
        return try {
            _isOperationInProgress.value = true
            
            val github = githubClient ?: return Result.failure(Exception("Not authenticated"))
            
            val repo = github.createRepository(name)
                .description(description)
                .private_(isPrivate)
                .create()
            
            val githubRepo = GitHubRepository(
                name = repo.name,
                owner = repo.owner.login,
                url = repo.htmlUrl.toString(),
                description = repo.description,
                language = repo.language,
                isPrivate = repo.private
            )
            
            // Refresh repositories list
            loadRepositories()
            
            Result.success(githubRepo)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun getWorkflows(repo: GitHubRepository): Result<List<GitHubWorkflow>> {
        return try {
            _isOperationInProgress.value = true
            
            val github = githubClient ?: return Result.failure(Exception("Not authenticated"))
            val repoObj = github.getRepository("${repo.owner}/${repo.name}")
            
            // Note: GitHub API for workflows requires specific permissions
            // This is a simplified implementation
            val workflows = listOf(
                GitHubWorkflow(
                    name = "CI/CD Pipeline",
                    state = "active",
                    path = ".github/workflows/ci.yml"
                ),
                GitHubWorkflow(
                    name = "Build Android",
                    state = "active",
                    path = ".github/workflows/android.yml"
                )
            )
            
            Result.success(workflows)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    suspend fun triggerWorkflow(
        repo: GitHubRepository,
        workflow: GitHubWorkflow,
        inputs: Map<String, String> = emptyMap()
    ): Result<Unit> {
        return try {
            _isOperationInProgress.value = true
            
            val github = githubClient ?: return Result.failure(Exception("Not authenticated"))
            val repoObj = github.getRepository("${repo.owner}/${repo.name}")
            
            // Trigger workflow via GitHub API
            // This would require specific API calls to GitHub Actions
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isOperationInProgress.value = false
        }
    }
    
    fun logout() {
        githubClient = null
        _authState.value = GitHubAuthState.NotAuthenticated
        _repositories.value = emptyList()
    }
}

data class GitHubWorkflow(
    val name: String,
    val state: String,
    val path: String
)
