package com.jarvis.assistant

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MusicActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var outputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        searchInput = findViewById(R.id.musicSearchInput)
        outputText = findViewById(R.id.musicOutputText)

        findViewById<TextView>(R.id.btnMusicPlay).setOnClickListener {
            outputText.text = MediaController.playMusic(this, searchInput.text.toString())
        }
        findViewById<TextView>(R.id.btnMusicPause).setOnClickListener {
            outputText.text = MediaController.pauseMusic(this)
        }
        findViewById<TextView>(R.id.btnMusicResume).setOnClickListener {
            outputText.text = MediaController.resumeMusic(this)
        }
        findViewById<TextView>(R.id.btnMusicNext).setOnClickListener {
            outputText.text = MediaController.nextTrack(this)
        }
        findViewById<TextView>(R.id.btnMusicStop).setOnClickListener {
            outputText.text = MediaController.stopMusic(this)
        }
    }
}
