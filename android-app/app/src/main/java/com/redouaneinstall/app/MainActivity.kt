package com.redouaneinstall.app

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ====================================================================
// ViewModel
// ====================================================================

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val input = MutableStateFlow("")
    val quality = MutableStateFlow("best")
    val history = MutableStateFlow<List<MediaStoreHelper.Item>>(emptyList())
    val msgs = MutableSharedFlow<String>(extraBufferCapacity = 8)

    sealed class InfoState {
        object Idle : InfoState()
        object Loading : InfoState()
        data class Ready(val meta: Engine.VideoMeta) : InfoState()
        data class Error(val msg: String) : InfoState()
    }

    val info = MutableStateFlow<InfoState>(InfoState.Idle)
    private var fetchJob: Job? = null

    private val urlRegex = Regex("https?://\\S+")

    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = urlRegex.find(text.trim()) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    fun setUrlFromShared(text: String?) {
        val url = extractUrl(text) ?: return
        input.value = url
        fetchInfo()
    }

    fun fetchInfo() {
        if (!Engine.ready.value) {
            msgs.tryEmit("تسنا شوية حتى يتجهز محرك التحميل...")
            return
        }
        val url = extractUrl(input.value)
        if (url == null) {
            msgs.tryEmit("ألصق رابط صحيح أولاً")
            return
        }
        fetchJob?.cancel()
        info.value = InfoState.Loading
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { Engine.extractMeta(url) }
            info.value = r.fold(
                onSuccess = { InfoState.Ready(it) },
                onFailure = { InfoState.Error(friendlyFetch(it)) }
            )
        }
    }

    private fun friendlyFetch(t: Throwable): String {
        val m = t.message ?: ""
        return when {
            m.contains("Unsupported URL") -> "هاد الرابط ماشي مدعوم"
            m.contains("Sign in to confirm", true) || m.contains("not a bot", true) ->
                "يوتيوب كيتحقق مؤقتاً (ضد الروبوتات) — المحرك كيتحدث تلقائياً، جرب من بعد دقايق"
            m.contains("Unable to", true) || m.contains("network", true) || m.contains("connect", true) ->
                "مشكل في الشبكة أو في الرابط، تثبت وجرب مرة أخرى"
            else -> "ما نقدرش نجيب معلومات الفيديو: ${m.take(100)}"
        }
    }

    fun startDownload() {
        val st = info.value as? InfoState.Ready ?: return
        val spec = DownloadService.Spec(
            url = st.meta.url,
            title = st.meta.title,
            quality = quality.value,
            processId = "job_${System.currentTimeMillis()}"
        )
        runCatching {
            DownloadService.enqueue(getApplication(), spec)
        }.onSuccess {
            msgs.tryEmit("بدات عملية التحميل")
        }.onFailure {
            msgs.tryEmit("ما قدرش يبدا التحميل — حل التطبيق وجرب مرة أخرى")
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            history.value = MediaStoreHelper.listDownloads(getApplication())
        }
    }
}

// ====================================================================
// Activity
// ====================================================================

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    var showSplash by remember { mutableStateOf(true) }
                    if (showSplash) {
                        SplashOverlay { showSplash = false }
                    } else {
                        HomeScreen(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** استقبال «مشاركة» من يوتيوب/تيك توك/إنستغرام... */
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            vm.setUrlFromShared(i.getStringExtra(Intent.EXTRA_TEXT))
            i.action = Intent.ACTION_MAIN
        }
    }
}

// ====================================================================
// واجهة المستخدم
// ====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    val input by vm.input.collectAsStateWithLifecycle()
    val infoState by vm.info.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val engineReady by Engine.ready.collectAsStateWithLifecycle()
    val engineStatus by Engine.status.collectAsStateWithLifecycle()
    val live by DownloadService.live.collectAsStateWithLifecycle()

    var clipboardUrl by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        clipboardUrl = runCatching { vm.extractUrl(clipboard.getText()?.text) }.getOrNull()
        vm.refreshHistory()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        vm.msgs.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        DownloadService.events.collect { ev ->
            when (ev) {
                is DownloadService.Event.Done -> {
                    Toast.makeText(ctx, "سالى التحميل وتسجل في مجلد التحميلات:\n${ev.name}", Toast.LENGTH_LONG).show()
                    vm.refreshHistory()
                }

                is DownloadService.Event.Failed ->
                    Toast.makeText(ctx, ev.msg, Toast.LENGTH_LONG).show()

                is DownloadService.Event.Info ->
                    Toast.makeText(ctx, ev.msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ---------- الترويسة ----------
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brand.GradientHeader)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Redouane Install",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "نفس فكرة SnapTube — تحميل من أكثر من 1000 موقع",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // ---------- حالة المحرك ----------
        if (!engineReady) {
            Surface(
                color = Brand.Surface2,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Brand.Red
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(engineStatus, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
            }
        }

        // ---------- خانة الرابط ----------
        OutlinedTextField(
            value = input,
            onValueChange = { vm.input.value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("ألصق رابط الفيديو هنا...") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null, tint = Brand.Muted) },
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { vm.input.value = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "مسح", tint = Brand.Muted)
                    }
                } else {
                    IconButton(onClick = {
                        runCatching { clipboard.getText()?.text }.getOrNull()?.let { vm.input.value = it }
                    }) {
                        Icon(painterResource(R.drawable.ic_paste), contentDescription = "لصق", tint = Brand.Red)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.fetchInfo() }),
            shape = RoundedCornerShape(16.dp)
        )

        val clipSuggestion = clipboardUrl
        if (clipSuggestion != null && clipSuggestion != input) {
            SuggestionChip(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                    vm.input.value = clipSuggestion
                    vm.fetchInfo()
                },
                label = { Text("رابط كاين في الحافظة — اضغط للصق والجلب") }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------- زر الجلب ----------
        Button(
            onClick = { vm.fetchInfo() },
            enabled = engineReady,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Red)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("جلب الفيديو", fontWeight = FontWeight.Bold)
        }

        // ---------- المنصات ----------
        Text(
            "ولّا دخل لأي منصة، كوبي الرابط ورد لهنا:",
            style = MaterialTheme.typography.bodySmall,
            color = Brand.Muted,
            modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            platforms.forEach { p ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { runCatching { uriHandler.openUri(p.url) } }
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(p.bg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(p.letter, color = p.fg, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(p.name, style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- حالة الجلب: خطأ / تحميل / نتيجة ----------
        when (val st = infoState) {
            is MainViewModel.InfoState.Idle -> Unit
            is MainViewModel.InfoState.Loading -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Brand.Red)
                    Spacer(Modifier.width(10.dp))
                    Text("جاري جلب معلومات الفيديو...", color = Brand.Muted)
                }
            }

            is MainViewModel.InfoState.Error -> {
                Surface(
                    color = Color(0xFF2A1414),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        st.msg,
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            is MainViewModel.InfoState.Ready -> {
                VideoCard(st.meta)
                QualityRow(quality) { vm.quality.value = it }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.startDownload() },
                    enabled = engineReady,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Red)
                ) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("تحميل الآن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---------- التقدم المباشر ----------
        live?.let { p ->
            Spacer(Modifier.height(16.dp))
            Surface(color = Brand.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (p.percent < 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Red)
                    } else {
                        LinearProgressIndicator(
                            progress = { p.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Brand.Red,
                            trackColor = Brand.Surface2
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("%.0f%%".format(p.percent.coerceAtLeast(0f)), color = Brand.Muted)
                        Spacer(Modifier.weight(1f))
                        if (p.etaSec in 0..86399) Text("باقي ${p.etaSec} ث", color = Brand.Muted)
                    }
                    TextButton(
                        onClick = { DownloadService.cancel(ctx) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("إلغاء", color = Brand.Red)
                    }
                }
            }
        }

        // ---------- التحميلات المحفوظة ----------
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("التحميلات ديالي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refreshHistory() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = Brand.Muted)
            }
        }
        if (history.isEmpty()) {
            Text(
                "ما كاين حتى تحميل دابا — الملفات كيتسجلو في مجلد Download/RedouaneInstall",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.Muted
            )
        } else {
            history.take(20).forEach { item ->
                Surface(
                    onClick = { openItem(ctx, item) },
                    color = Brand.Surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(if (item.isAudio) R.drawable.ic_music else R.drawable.ic_videocam),
                            contentDescription = null,
                            tint = Brand.Red,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(item.sizeText, style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
                        }
                    }
                }
            }
        }

        // ---------- تذييل ----------
        Spacer(Modifier.height(20.dp))
        Text(
            "استعمل التطبيق فقط للمحتوى اللي عندك الحق تحملو.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.Muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "مطوّر: Redouane El Moukhtatifi — 2026",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.Red,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(30.dp))
    }
}

// ====================================================================
// مكونات مساعدة
// ====================================================================

@Composable
private fun VideoCard(meta: Engine.VideoMeta) {
    Surface(color = Brand.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            AsyncImage(
                model = meta.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 120.dp, height = 80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.Surface2)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(meta.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatDur(meta.durationSec)} • ${meta.site}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Muted
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityRow(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "best" to "الأفضل (حتى 4K)",
        "p1080" to "1080p",
        "p720" to "720p",
        "p480" to "480p خفيف",
        "mp3" to "MP3 صوت فقط"
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Red,
                    selectedLabelColor = Color.White
                )
            )
        }
    }
}

private data class Platform(val name: String, val url: String, val letter: String, val bg: Color, val fg: Color)

private val platforms = listOf(
    Platform("YouTube", "https://m.youtube.com", "Y", Color(0xFFFF0033), Color.White),
    Platform("TikTok", "https://www.tiktok.com", "T", Color(0xFF25F4EE), Color(0xFF010101)),
    Platform("Instagram", "https://www.instagram.com", "I", Color(0xFFC13584), Color.White),
    Platform("Facebook", "https://m.facebook.com", "f", Color(0xFF1877F2), Color.White),
    Platform("X", "https://x.com", "X", Color(0xFFE7E9EA), Color(0xFF0F1419))
)

private fun formatDur(sec: Long): String {
    if (sec <= 0) return "--:--"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun openItem(ctx: Context, item: MediaStoreHelper.Item) {
    val (uri, mime) = MediaStoreHelper.openUriFor(ctx, item) ?: return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "ما كاين تطبيق كيفتح هاد النوع ديال الملفات", Toast.LENGTH_SHORT).show()
    }
}
