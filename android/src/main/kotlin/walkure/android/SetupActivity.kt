package walkure.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Main Entry Activity for Walkure Romanze on Android.
 *
 * If valid GameData archives are found, immediately transitions to [AndroidLauncher]. Otherwise,
 * displays a setup screen with a folder picker and transfer guide.
 */
class SetupActivity : Activity() {

  companion object {
    const val PREFS_NAME = "walkure_launcher_prefs"
    const val KEY_SAVED_DIR = "custom_game_dir"
    private const val REQUEST_CODE_PICK_FOLDER = 1001

    fun hasGamePacks(dir: File): Boolean {
      if (!dir.exists() || !dir.isDirectory) return false
      val checkDir = if (File(dir, "GameData").isDirectory) File(dir, "GameData") else dir
      val files = checkDir.listFiles() ?: return false
      return files.any { file ->
        val name = file.name.lowercase()
        name == "data0.pack" || (name.startsWith("data") && name.endsWith(".pack"))
      }
    }

    fun findGameDataDirectory(context: Context): File? {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val savedPath = prefs.getString(KEY_SAVED_DIR, null)
      if (savedPath != null) {
        val savedDir = File(savedPath)
        if (hasGamePacks(savedDir)) return savedDir
      }

      val candidates =
        listOfNotNull(
          context.getExternalFilesDir(null),
          File(context.getExternalFilesDir(null) ?: context.filesDir, "GameData"),
          context.filesDir,
          File(context.filesDir, "GameData"),
          File("/sdcard/Walkure"),
          File("/sdcard/Walkure/GameData"),
          File("/sdcard/Download/Walkure"),
          File("/sdcard/Download/Walkure/GameData"),
          File("/sdcard/Games/Walkure"),
          File(Environment.getExternalStorageDirectory(), "Walkure"),
          File(Environment.getExternalStorageDirectory(), "Walkure/GameData"),
        )

      return candidates.firstOrNull { hasGamePacks(it) }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    extractBundledAssets()

    val gameDir = findGameDataDirectory(this)
    if (gameDir != null) {
      startGame(gameDir)
      return
    }

    showSetupUi()
  }

  private fun extractBundledAssets() {
    val targetDir = getExternalFilesDir(null) ?: filesDir
    targetDir.mkdirs()

    val keysTarget = File(targetDir, "walkure.keys")
    if (!keysTarget.exists() || keysTarget.length() < 512L) {
      runCatching {
        assets.open("walkure.keys").use { input ->
          FileOutputStream(keysTarget).use { output ->
            input.copyTo(output)
          }
        }
      }
    }

    val fontTarget = File(targetDir, "WalkureCustomV4.ttf")
    if (!fontTarget.exists() || fontTarget.length() == 0L) {
      runCatching {
        assets.open("WalkureCustomV4.ttf").use { input ->
          FileOutputStream(fontTarget).use { output ->
            input.copyTo(output)
          }
        }
      }
    }
  }

  private fun showSetupUi() {
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#12141C"))
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_HORIZONTAL
      }

    val titleView =
      TextView(this).apply {
        text = "Walkure Romanze — Setup"
        textSize = 24f
        setTextColor(Color.parseColor("#F5D77F"))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
      }
    root.addView(titleView)

    val targetDir = getExternalFilesDir(null) ?: filesDir
    val instructions =
      TextView(this).apply {
        text =
          """
                Game archives (data0.pack..data15.pack) were not found.
                
                Please copy your PC game's GameData folder to your device:
                
                📁 Recommended Path:
                ${targetDir.absolutePath}
                
                Or put them in:
                /sdcard/Walkure/
                
                Once copied, tap 'Launch Game' or select your folder below:
            """
            .trimIndent()
        textSize = 14f
        setTextColor(Color.parseColor("#E0E4F0"))
        setPadding(0, 0, 0, (20 * resources.displayMetrics.density).toInt())
      }
    root.addView(instructions)

    val btnPickFolder =
      Button(this).apply {
        text = "📁 Select Game Folder"
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2A4365"))
        setOnClickListener {
          val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
              addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                  Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
              )
            }
          startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER)
        }
      }
    root.addView(btnPickFolder)

    val btnRefresh =
      Button(this).apply {
        text = "🔄 Launch Game"
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#276749"))
        setPadding(
          0,
          (12 * resources.displayMetrics.density).toInt(),
          0,
          (12 * resources.displayMetrics.density).toInt(),
        )
        setOnClickListener {
          val found = findGameDataDirectory(this@SetupActivity)
          if (found != null) {
            startGame(found)
          } else {
            Toast.makeText(
                this@SetupActivity,
                "data*.pack files still not found in standard directories. Please copy files or select folder.",
                Toast.LENGTH_LONG,
              )
              .show()
          }
        }
      }
    val btnParams =
      LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        .apply {
          topMargin = (12 * resources.displayMetrics.density).toInt()
        }
    root.addView(btnRefresh, btnParams)

    val btnHelp =
      Button(this).apply {
        text = "ℹ️ How to Transfer Files (USB)"
        setTextColor(Color.parseColor("#CBD5E0"))
        setBackgroundColor(Color.parseColor("#2D3748"))
        setOnClickListener {
          AlertDialog.Builder(this@SetupActivity)
            .setTitle("Transferring PC Game Data")
            .setMessage(
              "1. Connect your Android device to your computer via USB.\n" +
                "2. In Android notification, select 'File Transfer (MTP)'.\n" +
                "3. On your computer, open your device storage.\n" +
                "4. Navigate to:\n" +
                "   Android/data/org.walkure.android/files/\n" +
                "   (or create a 'Walkure' folder on internal storage).\n" +
                "5. Copy all 'data*.pack' files (data0.pack to data15.pack) into that folder.\n" +
                "6. Tap 'Launch Game'.",
            )
            .setPositiveButton("OK", null)
            .show()
        }
      }
    root.addView(btnHelp, btnParams)

    val scrollView =
      ScrollView(this).apply {
        addView(root)
      }
    setContentView(scrollView)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == RESULT_OK) {
      val treeUri = data?.data ?: return
      contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )

      val resolvedPath = resolveTreeUriToPath(treeUri)
      val candidate = if (resolvedPath != null) File(resolvedPath) else null

      if (candidate != null && hasGamePacks(candidate)) {
        startGame(candidate)
      } else {
        Toast.makeText(
            this,
            "Selected folder does not contain data*.pack files.",
            Toast.LENGTH_LONG,
          )
          .show()
      }
    }
  }

  private fun startGame(gameDir: File) {
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_SAVED_DIR, gameDir.absolutePath)
      .apply()

    val intent =
      Intent(this, AndroidLauncher::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
      }
    startActivity(intent)
    finish()
  }

  private fun resolveTreeUriToPath(treeUri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
    val parts = docId.split(":")
    if (parts.size >= 2) {
      val type = parts[0]
      val relativePath = parts[1]
      return if ("primary".equals(type, ignoreCase = true)) {
        "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
      } else {
        "/storage/$type/$relativePath"
      }
    }
    return null
  }
}
