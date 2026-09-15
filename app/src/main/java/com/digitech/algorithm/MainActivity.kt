package com.digitech.algorithm

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "DIGITECH ALGORITHM\n\nApp is working"
            textSize = 24f
            setPadding(40, 100, 40, 40)
        }

        setContentView(title)
    }
}
