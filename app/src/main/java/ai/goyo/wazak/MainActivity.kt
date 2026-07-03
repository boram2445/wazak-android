package ai.goyo.wazak

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.URL

class MainActivity : Activity() {
    private lateinit var repository: MalangiRepository
    private val supabase = SupabaseMalangiClient()
    private val recorder by lazy { AudioRecorder(this) }
    private val player = SoundPlayer()
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.Home
    private var pendingPkce: Pkce? = null
    private var pendingImageUri: Uri? = null
    private val pendingSounds = mutableListOf<Pair<Uri, String>>()
    private var marketplaceItems: List<MarketplaceMalangi> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var homeImage: ImageView
    private lateinit var homeName: TextView
    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText
    private lateinit var soundListText: TextView
    private lateinit var authText: TextView

    private val timerTick = object : Runnable {
        override fun run() {
            if (recorder.isRecording) {
                statusText.text = "녹음 중 ${formatSeconds(recorder.elapsedSeconds())}"
                handler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MalangiRepository(this)
        root = FrameLayout(this)
        setContentView(root)
        handleAuthCallback(intent)
        showHome()
        refreshRemoteQuietly()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    override fun onDestroy() {
        recorder.stopOrDiscard()
        player.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (mode == Mode.Settings || mode == Mode.Marketplace) showHome() else super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_PICK_IMAGE -> {
                pendingImageUri = data?.data
                renderSettings()
            }
            REQUEST_PICK_SOUND -> {
                pendingSounds.clear()
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        val uri = clip.getItemAt(i).uri
                        pendingSounds += uri to displayName(uri.toString())
                    }
                } ?: data?.data?.let { pendingSounds += it to displayName(it.toString()) }
                renderSettings()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toggleRecording()
        }
    }

    private fun showHome() {
        mode = Mode.Home
        root.removeAllViews()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(28), dp(42), dp(28), dp(28))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (repository.authSession == null) {
                addView(homeTopButton("로그인") { signInWithGoogle() })
            } else {
                addView(homeTopButton(repository.authSession?.displayName ?: "계정") { refreshAccount() })
            }
            addView(homeTopButton("말랑이 마켓") { loadMarketplace() })
            addView(homeTopButton("말랑이 관리") { showSettings() })
        }
        content.addView(topBar, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(24)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))

        row.addView(arrowButton("<") { moveSelection(-1) })

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { playSelectedSound() }
        }
        row.addView(center, LinearLayout.LayoutParams(0, -1, 1f))

        homeImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setOnClickListener { playSelectedSound() }
            setOnLongClickListener {
                showSettings()
                true
            }
        }
        center.addView(homeImage, LinearLayout.LayoutParams(-1, dp(360)))

        homeName = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { playSelectedSound() }
            setOnLongClickListener {
                showSettings()
                true
            }
        }
        center.addView(homeName, LinearLayout.LayoutParams(-1, -2))

        row.addView(arrowButton(">") { moveSelection(1) })
        renderHome()
    }

    private fun homeTopButton(text: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = rounded(CARD, dp(22).toFloat(), 0x1AF06F5A, 1)
            elevation = dp(5).toFloat()
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(8)
            }
        }

    private fun arrowButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(72, -1)
        }

    private fun renderHome() {
        val current = repository.selected()
        homeName.text = current?.name ?: "말랑이"
        loadImage(homeImage, current?.imagePath)
    }

    private fun playSelectedSound() {
        val sound = repository.selected()?.soundPaths?.randomOrNull()
        if (sound == null) {
            toast("등록된 사운드가 없어요")
            return
        }
        player.play(sound)
    }

    private fun moveSelection(delta: Int) {
        val list = repository.all()
        if (list.isEmpty()) return
        repository.selectedIndex = (repository.selectedIndex + delta + list.size) % list.size
        renderHome()
        startService(Intent(this, FloatingMalangiService::class.java))
    }

    private fun showSettings() {
        mode = Mode.Settings
        renderSettings()
    }

    private fun renderSettings() {
        root.removeAllViews()
        val scroll = ScrollView(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(72), dp(20), dp(36))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val current = repository.selected()
        content.addView(topHeader("말랑이 관리"))
        content.addView(section("말랑이 관리").apply {
            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(0x22F06F5A, dp(18).toFloat())
            }
            addView(preview, LinearLayout.LayoutParams(-1, 380))
            loadImage(preview, pendingImageUri?.toString() ?: current?.imagePath)

            nameInput = EditText(context).apply {
                hint = "말랑이 이름"
                setText(current?.name.orEmpty())
                textSize = 16f
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                background = rounded(0xFFFFFBF7.toInt(), dp(14).toFloat(), 0x22F06F5A, 1)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            addView(nameInput, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
            addView(button("말랑이 사진 등록") { pickImage() })
            addView(button("사운드 여러 개 등록") { pickSounds() })
            addView(button("현재 입력 저장") { saveDraft() })
            addView(button("현재 말랑이 삭제") { repository.deleteCurrent(); pendingImageUri = null; pendingSounds.clear(); renderSettings() })
        })

        content.addView(section("등록된 사운드").apply {
            soundListText = smallText(soundSummary(current))
            addView(soundListText)
            statusText = smallText("")
            addView(statusText)
            addView(button("녹음 시작 / 정지") { requestRecordingOrToggle() })
            addView(button("미리듣기") {
                repository.selected()?.soundPaths?.firstOrNull()?.let(player::play)
            })
        })

        content.addView(section("플로팅").apply {
            addView(button("오버레이 권한 열기") { openOverlaySettings() })
            addView(button("말랑이 시작") { startMalangi() })
            addView(button("말랑이 숨기기") { stopService(Intent(this@MainActivity, FloatingMalangiService::class.java)) })
        })

        content.addView(section("Supabase").apply {
            authText = smallText(accountSummary())
            addView(authText)
            addView(button("세션 새로고침") { refreshAccount() })
            addView(button("닉네임 저장") { updateNicknameFromInput() })
            addView(button("기본 말랑이 불러오기") { refreshRemoteLoudly() })
            addView(button("내 백업 가져오기") { syncBackups() })
            addView(button("현재 말랑이 백업") { backupCurrent() })
            addView(button(current?.marketplaceId?.let { "마켓에서 내리기" } ?: "마켓에 올리기") { toggleMarketplace() })
            addView(button("마켓 목록 보기") { loadMarketplace() })
            addView(button("로그아웃") { repository.authSession = null; renderSettings() })
        })

        content.addView(button("홈으로") { pendingImageUri = null; pendingSounds.clear(); showHome() })
    }

    private fun section(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = rounded(CARD, dp(18).toFloat(), 0x14F06F5A, 1)
            elevation = dp(3).toFloat()
            addView(sectionTitle(title))
            val params = LinearLayout.LayoutParams(-1, -2)
            params.setMargins(0, 0, 0, dp(14))
            layoutParams = params
        }

    private fun topHeader(text: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
            addView(TextView(context).apply {
                this.text = "‹"
                textSize = 38f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setOnClickListener { showHome() }
            }, LinearLayout.LayoutParams(dp(48), dp(52)))
            addView(TextView(context).apply {
                this.text = text
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        }

    private fun smallText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(8))
        }

    private fun button(text: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (text.contains("삭제") || text.contains("로그아웃") || text.contains("내리기")) 0xFFD64545.toInt() else TEXT)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = when {
                text.contains("저장") || text.contains("로그인") || text.contains("시작") || text == "받기" ->
                    rounded(ACCENT_PINK, dp(22).toFloat())
                text.contains("삭제") || text.contains("로그아웃") || text.contains("내리기") ->
                    rounded(0x14D64545, dp(22).toFloat(), 0x22D64545, 1)
                else ->
                    rounded(ACCENT_BLUE_SOFT, dp(22).toFloat(), 0x1A7EA4D8, 1)
            }
            if (text.contains("저장") || text.contains("로그인") || text.contains("시작") || text == "받기") {
                setTextColor(0xFFFFFFFF.toInt())
            }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }

    private fun pickImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_PICK_IMAGE)
    }

    private fun pickSounds() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_PICK_SOUND)
    }

    private fun saveDraft() {
        val existing = repository.selected()?.takeUnless { it.isDefault }
        if (pendingImageUri == null && existing == null) {
            toast("새 말랑이는 사진이 필요해요")
            return
        }
        repository.createOrUpdate(existing, nameInput.text.toString(), pendingImageUri, pendingSounds.takeIf { it.isNotEmpty() })
        pendingImageUri = null
        pendingSounds.clear()
        renderSettings()
    }

    private fun requestRecordingOrToggle() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) toggleRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            val file = File(filesDir, "last-recording.m4a")
            recorder.stop()
            repository.selected()?.takeUnless { it.isDefault }?.let { repository.addRecordedSound(it, file) }
            handler.removeCallbacks(timerTick)
            renderSettings()
        } else {
            recorder.start(File(filesDir, "last-recording.m4a"))
            handler.post(timerTick)
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startMalangi() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            toast("다른 앱 위에 표시 권한을 켜 주세요")
            return
        }
        startService(Intent(this, FloatingMalangiService::class.java))
    }

    private fun signInWithGoogle() {
        if (!supabase.isConfigured) return toast("Supabase 설정이 필요해요")
        val pkce = Pkce.create()
        pendingPkce = pkce
        getSharedPreferences("wazak", MODE_PRIVATE).edit().putString("pkceVerifier", pkce.verifier).apply()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(supabase.googleSignInUrl(pkce))))
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "wazak") return
        val code = uri.getQueryParameter("code") ?: return
        val verifier = pendingPkce?.verifier ?: getSharedPreferences("wazak", MODE_PRIVATE).getString("pkceVerifier", null)
        if (verifier == null) return toast("로그인 검증값을 찾을 수 없어요")
        runAsync("로그인 완료") {
            repository.authSession = supabase.exchangeCodeForSession(code, verifier)
            getSharedPreferences("wazak", MODE_PRIVATE).edit().remove("pkceVerifier").apply()
        }
    }

    private fun refreshAccount() {
        val session = repository.authSession ?: return
        runAsync("계정 새로고침 완료") {
            repository.authSession = supabase.refreshUser(session)
        }
    }

    private fun updateNicknameFromInput() {
        val session = repository.authSession ?: return toast("로그인이 필요해요")
        val nickname = nameInput.text.toString().trim()
        if (nickname.isBlank()) return toast("닉네임으로 쓸 이름을 입력해 주세요")
        runAsync("닉네임 저장 완료") {
            repository.authSession = supabase.updateNickname(session, nickname)
        }
    }

    private fun refreshRemoteQuietly() {
        if (!supabase.isConfigured) return
        Thread {
            runCatching { repository.saveRemoteCatalog(supabase.fetchCatalog()) }
            runOnUiThread { if (mode == Mode.Home) renderHome() }
        }.start()
    }

    private fun refreshRemoteLoudly() {
        runAsync("기본 말랑이를 불러왔어요") {
            repository.saveRemoteCatalog(supabase.fetchCatalog())
        }
    }

    private fun syncBackups() {
        val session = repository.authSession ?: return toast("로그인이 필요해요")
        runAsync("백업을 가져왔어요") {
            supabase.fetchMyBackups(session).forEach(repository::saveLocal)
        }
    }

    private fun backupCurrent() {
        val session = repository.authSession ?: return toast("로그인이 필요해요")
        val current = repository.selected()?.takeUnless { it.isDefault } ?: return toast("로컬 말랑이만 백업할 수 있어요")
        runAsync("백업 완료") { supabase.backupMalangi(current, session) }
    }

    private fun toggleMarketplace() {
        val session = repository.authSession ?: return toast("로그인이 필요해요")
        val current = repository.selected()?.takeUnless { it.isDefault } ?: return toast("로컬 말랑이만 마켓에 올릴 수 있어요")
        runAsync("마켓 상태가 바뀌었어요") {
            if (current.marketplaceId == null) {
                val row = supabase.publish(current, session)
                repository.setMarketplaceId(current.localId, row.id)
            } else {
                supabase.unpublish(current.marketplaceId, session)
                repository.setMarketplaceId(current.localId, null)
            }
        }
    }

    private fun loadMarketplace() {
        status("마켓 불러오는 중…")
        Thread {
            val result = runCatching {
                val builtIns = supabase.fetchCatalog().map {
                    MarketplaceMalangi(it.remoteId.orEmpty(), null, null, "Wazak", it.name, it.imagePath, it.imageFileName, it.soundPaths, it.soundFileNames, 0, true)
                }
                builtIns + supabase.fetchMarketplace()
            }
            runOnUiThread {
                result.onSuccess {
                    marketplaceItems = it
                    renderMarketplace()
                }.onFailure {
                    toast(it.message ?: "마켓을 불러오지 못했어요")
                }
            }
        }.start()
    }

    private fun renderMarketplace() {
        mode = Mode.Marketplace
        root.removeAllViews()
        val scroll = ScrollView(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(72), dp(20), dp(36))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        content.addView(topHeader("말랑이 마켓"))
        content.addView(smallText(if (repository.authSession == null) "공유 말랑이 다운로드는 로그인이 필요해요. 기본 말랑이는 바로 받을 수 있어요." else "공유 말랑이는 다운로드 시 포인트를 사용해요."))
        if (marketplaceItems.isEmpty()) {
            content.addView(section("목록").apply { addView(smallText("아직 등록된 말랑이가 없어요.")) })
        } else {
            marketplaceItems.forEach { item ->
                content.addView(marketplaceRow(item))
            }
        }
        content.addView(button("설정으로") { renderSettings() })
        content.addView(button("홈으로") { showHome() })
    }

    private fun marketplaceRow(item: MarketplaceMalangi): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, dp(18).toFloat(), 0x1AFFB5C2, 1)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }

            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(0x26FFB5C2, dp(14).toFloat())
            }
            addView(preview, LinearLayout.LayoutParams(dp(92), dp(92)).apply { rightMargin = dp(12) })
            loadImage(preview, item.imageURL)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = item.name
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT)
                    maxLines = 1
                })
                addView(smallText(listOfNotNull(
                    if (item.isBuiltIn) "기본" else "공유",
                    item.ownerName ?: item.ownerEmail,
                    if (!item.isBuiltIn) "다운로드 ${item.downloadsCount}" else null
                ).joinToString(" · ")))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(smallPill("미리듣기", false) { item.soundURLs.firstOrNull()?.let(player::play) })
                    addView(smallPill("받기", true) { downloadMarketplaceItem(item) })
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

    private fun smallPill(text: String, primary: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else TEXT)
            background = rounded(if (primary) ACCENT_PINK else ACCENT_BLUE_SOFT, dp(18).toFloat())
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        }

    private fun downloadMarketplaceItem(item: MarketplaceMalangi) {
        val session = repository.authSession
        if (!item.isBuiltIn && session == null) return toast("로그인이 필요해요")
        Thread {
            val result = runCatching {
                if (!item.isBuiltIn && session != null) supabase.downloadMarketplace(item.id, session)
                repository.addFromMarketplace(item)
            }
            runOnUiThread {
                result.onSuccess {
                    toast("받았어요")
                    renderMarketplace()
                }.onFailure {
                    toast(it.message ?: "다운로드 실패")
                }
            }
        }.start()
    }

    private fun runAsync(doneMessage: String, block: () -> Unit) {
        status("처리 중…")
        Thread {
            val result = runCatching(block)
            runOnUiThread {
                result.onSuccess { toast(doneMessage) }
                    .onFailure { toast(it.message ?: "실패했어요") }
                if (mode == Mode.Settings) renderSettings() else renderHome()
            }
        }.start()
    }

    private fun loadImage(view: ImageView, reference: String?) {
        view.setImageDrawable(null)
        if (reference.isNullOrBlank()) {
            view.setImageResource(ai.goyo.wazak.R.drawable.ic_launcher)
            return
        }
        if (reference.startsWith("http://") || reference.startsWith("https://")) {
            Thread {
                val bitmap = runCatching { URL(reference).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
                runOnUiThread { if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageResource(R.drawable.ic_launcher) }
            }.start()
        } else {
            view.setImageBitmap(BitmapFactory.decodeFile(reference))
        }
    }

    private fun soundSummary(current: StoredMalangi?): String {
        val pending = if (pendingSounds.isEmpty()) "" else "\n선택됨: ${pendingSounds.size}개"
        return (current?.soundFileNames?.joinToString("\n") ?: "사운드를 등록해 주세요") + pending
    }

    private fun accountSummary(): String {
        val session = repository.authSession ?: return "로그인하면 백업과 마켓 기능을 쓸 수 있어요."
        return listOfNotNull(session.displayName ?: session.email, "id: ${session.userId.take(8)}").joinToString("\n")
    }

    private fun status(text: String) {
        if (::statusText.isInitialized) statusText.text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun formatSeconds(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private enum class Mode { Home, Settings, Marketplace }

    companion object {
        private const val BACKGROUND = 0xFFFFF7ED.toInt()
        private const val CARD = 0xFFFFFFFF.toInt()
        private const val TEXT = 0xFF231F20.toInt()
        private const val MUTED = 0xFF6F6460.toInt()
        private const val ACCENT_PINK = 0xFFFFB5C2.toInt()
        private const val ACCENT_BLUE_SOFT = 0x40A8C7EB
        private const val REQUEST_PICK_IMAGE = 10
        private const val REQUEST_RECORD_AUDIO = 11
        private const val REQUEST_PICK_SOUND = 12
    }
}
