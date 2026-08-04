# AI DApp Developer

An Android application for creating, modifying, and deploying decentralized applications (dapps) with AI assistance. Features local AI models for text and code generation, complete GitHub integration, and an AI-powered terminal.

## Features

### 🤖 Local AI Models
- **Multiple AI Models**: Support for LLaMA, CodeLlama, Mistral, and DeepSeek models
- **Text Generation**: Generate natural language responses and explanations
- **Code Generation**: Create code snippets and complete functions
- **On-Device Inference**: All AI processing happens locally on your device

### 🔗 GitHub Integration
- **Authentication**: Secure GitHub token-based authentication
- **Repository Management**: Clone, create, and manage GitHub repositories
- **Git Operations**: Full support for commit, push, pull, and branch operations
- **Workflow Support**: Trigger and manage GitHub Actions workflows

### 💻 AI-Powered Terminal
- **Command Execution**: Run terminal commands directly from the app
- **AI Assistance**: Get AI explanations and suggestions for commands
- **Git Integration**: AI-enhanced git operations (smart commits, status analysis)
- **Workflow Commands**: Execute GitHub workflows with AI assistance

### 📱 DApp Templates
- **Cross-Platform**: Templates for Android, iOS, Windows, and cross-platform apps
- **Multiple Categories**: Blockchain, DeFi, NFT, Gaming, Social, and Utility dapps
- **AI Generation**: Generate custom dapps based on your descriptions
- **Code Enhancement**: AI-powered code improvement and optimization

## Project Structure

```
AI_Dapp_Developer/
├── app/
│   ├── src/main/
│   │   ├── java/com/ai/dapp/developer/
│   │   │   ├── ai/                    # AI model management
│   │   │   │   └── AIModelManager.kt
│   │   │   ├── github/                # GitHub integration
│   │   │   │   └── GitHubManager.kt
│   │   │   ├── terminal/              # Terminal functionality
│   │   │   │   └── TerminalManager.kt
│   │   │   ├── dapp/                  # DApp templates
│   │   │   │   └── DappTemplateManager.kt
│   │   │   ├── service/               # Background services
│   │   │   │   ├── AIInferenceService.kt
│   │   │   │   ├── GitHubAuthService.kt
│   │   │   │   └── TerminalService.kt
│   │   │   ├── ui/                    # User interface
│   │   │   │   ├── navigation/
│   │   │   │   ├── screens/
│   │   │   │   └── theme/
│   │   │   ├── MainActivity.kt
│   │   │   └── AI_Dapp_Application.kt
│   │   ├── res/                       # Resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── keystore.properties
```

## Technology Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material3
- **Dependency Injection**: Hilt
- **Navigation**: Jetpack Navigation Compose
- **AI Inference**: TensorFlow Lite, MLC LLM
- **GitHub API**: Kohsuke GitHub API, JGit
- **Terminal**: Termux Terminal View
- **Async**: Kotlin Coroutines & Flow

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Gradle 8.2

### Building the Project

1. Clone the repository:
```bash
git clone <repository-url>
cd AI_Dapp_Developer
```

2. Open the project in Android Studio

3. Configure keystore for release builds:
```bash
# Create keystore
keytool -genkey -v -keystore release.keystore -alias your_key_alias -keyalg RSA -keysize 2048 -validity 10000

# Update keystore.properties with your credentials
```

4. Build the project:
```bash
./gradlew assembleDebug    # For debug build
./gradlew assembleRelease  # For release build
```

5. Install on device:
```bash
./gradlew installDebug
```

### Running Tests

```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Configuration

### AI Models

AI models are downloaded and stored locally. To add new models:

1. Place model files in `app/src/main/assets/ai_models/`
2. Update `AIModelManager.kt` with model metadata
3. Models are loaded on-demand to save storage

### GitHub Authentication

1. Generate a Personal Access Token at github.com/settings/tokens
2. Required scopes: `repo`, `workflow`, `read:org`
3. Enter the token in the app's GitHub settings

### Terminal Permissions

The app requires the following permissions:
- `INTERNET` - For GitHub API and model downloads
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` - For file operations
- `MANAGE_EXTERNAL_STORAGE` - For full file system access
- `FOREGROUND_SERVICE` - For background AI inference

## Usage

### Creating a DApp

1. Navigate to **DApp Templates**
2. Select a platform (Android, iOS, Windows, or Cross-Platform)
3. Choose a template or create a custom one with AI
4. Edit files in the built-in code editor
5. Use AI enhancement to improve code
6. Export or push to GitHub

### Using the AI Terminal

1. Navigate to **Terminal**
2. Enter commands in the input field
3. Enable "AI Assist" for intelligent suggestions
4. Use quick action buttons for common git commands
5. View command history and output

### GitHub Integration

1. Navigate to **GitHub**
2. Connect with your Personal Access Token
3. Browse and clone repositories
4. Perform git operations with AI assistance
5. Trigger GitHub workflows

## AI Model Support

The app supports the following local AI models:

- **LLaMA-2-7B-Chat**: General purpose text generation
- **CodeLlama-7B-Python**: Python code specialization
- **CodeLlama-7B-Instruct**: Instruction-following code generation
- **Mistral-7B-Instruct**: High-quality instruction following
- **DeepSeek-Coder-6.7B**: Advanced code generation

## Security Considerations

- GitHub tokens are stored securely using Android Keystore
- All AI processing happens on-device (no data sent to external servers)
- File operations respect Android storage permissions
- Network connections use HTTPS

## Performance Optimization

- AI models are loaded on-demand to minimize memory usage
- Terminal commands execute in background services
- GitHub operations use efficient pagination
- UI uses lazy loading for large lists

## Troubleshooting

### Build Issues

If you encounter build errors:
1. Clean the project: `./gradlew clean`
2. Invalidate caches in Android Studio
3. Check JDK version (requires JDK 17)
4. Update Android SDK to API 34

### AI Model Issues

If AI models fail to load:
1. Check available storage space
2. Verify model files are not corrupted
3. Ensure device has sufficient RAM (4GB+ recommended)

### GitHub Authentication

If GitHub authentication fails:
1. Verify your Personal Access Token is valid
2. Check that required scopes are granted
3. Ensure network connectivity
4. Check token expiration date

## Contributing

Contributions are welcome! Please follow these guidelines:

1. Fork the repository
2. Create a feature branch
3. Make your changes with clear commit messages
4. Test thoroughly on multiple devices
5. Submit a pull request

## License

MIT License - See LICENSE file for details

## Acknowledgments

- TensorFlow Lite team for the ML framework
- MLC AI for the LLM inference engine
- GitHub API maintainers
- Termux for the terminal emulator
- Android Jetpack team for the excellent libraries

## Support

For issues, questions, or feature requests:
- Open an issue on GitHub
- Check existing documentation
- Contact the development team

## Roadmap

- [ ] Support for more AI models (Gemma, Phi, etc.)
- [ ] Cloud AI model fallback option
- [ ] Enhanced code editor with syntax highlighting
- [ ] Project templates for specific blockchain networks
- [ ] Built-in blockchain testing environment
- [ ] Collaboration features for team development
- [ ] Integration with popular IDEs
- [ ] Web version of the app

## Version History

### v1.0.0 (Current)
- Initial release
- Local AI model support
- GitHub integration
- AI-powered terminal
- DApp templates for multiple platforms
- Cross-platform dapp generation
