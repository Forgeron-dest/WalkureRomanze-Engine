package walkure.android

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import walkure.gdx.WalkureGdxGame
import walkure.pack.HonorificsMode
import walkure.pack.QlieVirtualFileSystem
import java.io.File

/**
 * LibGDX OpenGL Game Runner Activity for Walkure Romanze.
 * Launched by [SetupActivity] once GameData is verified.
 */
class AndroidLauncher : AndroidApplication() {
    private var videoPlayer: AndroidVideoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameDir = SetupActivity.findGameDataDirectory(this)
        if (gameDir == null) {
            val setupIntent = Intent(this, SetupActivity::class.java)
            startActivity(setupIntent)
            finish()
            return
        }

        val defaultDir = getExternalFilesDir(null) ?: filesDir
        val sourceKey = File(defaultDir, "walkure.keys")
        val targetKey = File(gameDir, "walkure.keys")
        if (sourceKey.exists() && (!targetKey.exists() || targetKey.length() < 512L)) {
            runCatching { sourceKey.copyTo(targetKey, overwrite = true) }
        }

        val config = AndroidApplicationConfiguration().apply {
            useAccelerometer = false
            useCompass = false
            useImmersiveMode = true
        }

        val vfs = QlieVirtualFileSystem(gameDir, HonorificsMode.HON)
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val player = AndroidVideoPlayer(this, container, vfs)
        videoPlayer = player

        val game = WalkureGdxGame(
            vfs = vfs,
            initialScriptQuery = null,
            customVideoPlayer = player
        )

        val gameView = initializeForView(game, config)
        container.addView(gameView)
        setContentView(container)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && videoPlayer?.isPlaying() == true) {
            videoPlayer?.stop()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        videoPlayer?.dispose()
        super.onDestroy()
    }
}
