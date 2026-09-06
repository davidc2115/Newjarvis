package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SmartHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home)

        findViewById<TextView>(R.id.btnOpenHomeAssistant).setOnClickListener {
            startActivity(Intent(this, HomeAssistantActivity::class.java))
        }
        findViewById<TextView>(R.id.btnOpenNetwork).setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
        findViewById<TextView>(R.id.btnOpenMusic).setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }
        findViewById<TextView>(R.id.btnOpenGeneration).setOnClickListener {
            startActivity(Intent(this, GenerationActivity::class.java))
        }
        findViewById<TextView>(R.id.btnOpenFreebox).setOnClickListener {
            startActivity(Intent(this, FreeboxActivity::class.java))
        }
    }
}
