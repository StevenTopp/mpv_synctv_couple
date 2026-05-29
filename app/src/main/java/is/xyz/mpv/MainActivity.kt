package `is`.xyz.mpv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setTitle(R.string.mpv_activity)

        // The original plan was to have the file/doc picker live as fragments
        // under here but that requires refactoring I'm really not willing to figure out now.
        // ~sfan5, 2022-06-30

        if (savedInstanceState == null) {
            with (supportFragmentManager.beginTransaction()) {
                setReorderingAllowed(true)
                add(R.id.fragment_container_view, MainScreenFragment())
                commit()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val nightMode = prefs.getBoolean("night_mode", false)
        val color = if (nightMode) 0xFF8F76AD.toInt() else 0xFF6FA4E3.toInt()
        supportActionBar?.title = "一起看电影吖"
        supportActionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
    }
}
