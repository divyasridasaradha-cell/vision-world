package com.visionworld.launcher

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("vl", Context.MODE_PRIVATE) }
    private val wallpapers = mutableStateListOf<Uri>()
    private var index by mutableIntStateOf(0)
    private var apps by mutableStateOf(emptyList<AppItem>())

    private val pick = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { u ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (!wallpapers.contains(u)) wallpapers.add(u)
        }
        prefs.edit().putStringSet("w", wallpapers.map { it.toString() }.toSet()).apply()
    }

    private val voice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val text = r.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.lowercase() ?: return@registerForActivityResult
        handleCommand(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.getStringSet("w", emptySet())?.forEach { wallpapers.add(Uri.parse(it)) }
        setContent { Home() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            listen(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Voice ----------
    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN") // English kosam "en-IN"
        try { voice.launch(i) } catch (e: Exception) {
            Toast.makeText(this, "Voice not available on this TV", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleCommand(t: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            "volume" in t || "వాల్యూమ్" in t || "sound" in t || "సౌండ్" in t -> {
                val up = listOf("up", "increase", "పెంచు", "penchu").any { it in t }
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            "wallpaper" in t || "వాల్" in t || "వాల్‌పేపర్" in t -> index++
            "youtube" in t || "యూట్యూబ్" in t -> openPkg("com.google.android.youtube.tv")
            else -> {
                val match = apps.firstOrNull { it.label.lowercase() in t }
                if (match != null) openPkg(match.pkg)
                else Toast.makeText(this, "\"$t\" ardham kaledu", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Apps ----------
    private fun openPkg(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        var list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        if (list.isEmpty()) list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        return list.filter { it.activityInfo.packageName != packageName }.map {
            AppItem(
                it.loadLabel(pm).toString(),
                it.activityInfo.packageName,
                it.loadIcon(pm).toBitmap(160, 160).asImageBitmap()
            )
        }
    }

    private fun decode(u: Uri): ImageBitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / s > 2000) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?.asImageBitmap()
    }.getOrNull()

    // ---------- UI ----------
    @Composable
    fun Home() {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps() } }
        LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
        LaunchedEffect(wallpapers.size) {
            while (true) { delay(15000); if (wallpapers.size > 1) index++ }
        }

        val uri = if (wallpapers.isEmpty()) null else wallpapers[index % wallpapers.size]

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Crossfade(targetState = uri, animationSpec = tween(1200), label = "wp") { u ->
                var bmp by remember(u) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(u) {
                    bmp = if (u == null) null else withContext(Dispatchers.IO) { decode(u) }
                }
                val b = bmp
                if (b != null) {
                    Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                            )
                        )
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0x66000000), Color.Transparent, Color(0xCC000000))
                    )
                )
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column {
                        Text(
                            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now),
                            color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light
                        )
                        Text(
                            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now),
                            color = Color(0xCCFFFFFF), fontSize = 20.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FocusCard({ listen() }) { Pill("🎤  Voice") }
                        FocusCard({ pick.launch(arrayOf("image/*")) }) { Pill("🖼  Wallpapers") }
                        FocusCard({ index++ }) { Pill("⏭  Next") }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Apps", color = Color.White, fontSize = 22.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp)
                ) {
                    items(apps) { a ->
                        FocusCard({ openPkg(a.pkg) }, Modifier.size(width = 160.dp, height = 150.dp)) {
                            Column(
                                Modifier.fillMaxSize().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Image(a.icon, null, Modifier.size(72.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    a.label, color = Color.White, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Pill(t: String) {
        Text(t, color = Color.White, fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }

    @Composable
    fun FocusCard(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        content: @Composable BoxScope.() -> Unit
    ) {
        var focused by remember { mutableStateOf(false) }
        val s by animateFloatAsState(if (focused) 1.12f else 1f, tween(200), label = "s")
        val shape = RoundedCornerShape(20.dp)
        Box(
            modifier
                .scale(s)
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
                .clip(shape)
                .background(Color.White.copy(alpha = if (focused) 0.28f else 0.14f))
                .clickable(onClick = onClick),
            content = content
        )
    }
}