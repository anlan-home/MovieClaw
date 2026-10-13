package io.movieclaw.android.feature.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import io.movieclaw.android.core.designsystem.AccentSoft
import androidx.compose.material.icons.rounded.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.Danger
import io.movieclaw.android.core.designsystem.GlassCard
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.model.AgentSessionStart
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AgentNewSessionViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val apiFactory: ApiFactory,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    data class UiState(
        val input: String = "",
        val creating: Boolean = false,
        val error: String? = null,
        /** 可选模型（`GET /llm/models`）；与对话页同一套选择器 */
        val models: List<io.movieclaw.android.core.model.LlmModelOption> = emptyList(),
        /** null = 智能体默认 */
        val modelRef: String? = null,
        /** null = 由模型自行决定 */
        val thinkingLevel: String? = null,
        /** 已上传的图片附件（首条消息一起发出去） */
        val attachments: List<io.movieclaw.android.core.model.AgentAttachment> = emptyList(),
        val uploading: Boolean = false,
        val notice: io.movieclaw.android.core.designsystem.McNotice? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    init {
        // 与对话页同一份模型清单（同一个端点；失败不影响建会话）
        viewModelScope.launch {
            val origin = sessionRepository.ui.value.origin ?: return@launch
            runCatching { apiFactory.forOrigin(origin).llmModels().dataOrThrow() }
                .onSuccess { list ->
                    val choice = AgentComposerPrefs.reconcile(AgentComposerPrefs.load(context), list)
                    AgentComposerPrefs.save(context, choice)
                    _ui.update { it.copy(models = list, modelRef = choice.model, thinkingLevel = choice.thinking) }
                }
        }
    }

    fun onInput(value: String) = _ui.update { it.copy(input = value) }

    fun setModel(ref: String?) {
        _ui.update { it.copy(modelRef = ref, thinkingLevel = null) }
        AgentComposerPrefs.save(context, AgentComposerPrefs.Choice(ref, null))
    }

    fun setThinkingLevel(level: String?) {
        _ui.update { it.copy(thinkingLevel = level) }
        AgentComposerPrefs.save(context, AgentComposerPrefs.Choice(_ui.value.modelRef, level))
    }

    fun consumeNotice() = _ui.update { it.copy(notice = null) }

    /** 图片附件：读字节 → multipart 上传 → 记录 attachment_id（与对话页同一份实现） */
    fun attachImage(uri: android.net.Uri) {
        _ui.update { it.copy(uploading = true) }
        viewModelScope.launch {
            val origin = sessionRepository.ui.value.origin
            if (origin == null) {
                _ui.update { it.copy(uploading = false) }   // 别把"上传中…"挂在界面上
                return@launch
            }
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                _ui.update {
                    it.copy(
                        uploading = false,
                        notice = io.movieclaw.android.core.designsystem.McNotice("无法读取所选图片", io.movieclaw.android.core.designsystem.FeedbackTone.Error),
                    )
                }
                return@launch
            }
            // 扩展名/MIME 都按真实类型来（原来是写死的 .jpg + image/jpeg，PNG 会被当成 JPEG 传上去）
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/heic", "image/heif" -> "heic"
                else -> "jpg"
            }
            val temp = java.io.File(context.cacheDir, "agent_upload_${System.currentTimeMillis()}.$ext")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { temp.writeBytes(bytes) } }
            runCatching {
                val part = okhttp3.MultipartBody.Part.createFormData(
                    "file", temp.name, temp.asRequestBody(mime.toMediaType()),
                )
                apiFactory.forOrigin(origin).uploadAgentAttachment(part).dataOrThrow()
            }
                .onSuccess { attachment -> _ui.update { it.copy(uploading = false, attachments = it.attachments + attachment) } }
                .onFailure { e ->
                    _ui.update {
                        it.copy(
                            uploading = false,
                            notice = io.movieclaw.android.core.designsystem.McNotice(friendlyMessage(e), io.movieclaw.android.core.designsystem.FeedbackTone.Error),
                        )
                    }
                }
            // 传完就删临时文件（原来一直堆在 cacheDir 里）
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { temp.delete() } }
        }
    }

    fun removeAttachment(id: String) = _ui.update { state ->
        state.copy(attachments = state.attachments.filterNot { it.attachmentId == id })
    }

    fun create(onCreated: (String) -> Unit) {
        val state = _ui.value
        val text = state.input.trim()
        // 与按钮同一个条件：要有文字或图片、不在创建中、也不在上传中（上传没完就建会话会把图丢掉）
        if (state.creating || state.uploading) return
        if (text.isEmpty() && state.attachments.isEmpty()) return
        _ui.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            val origin = sessionRepository.ui.value.origin
            if (origin == null) {
                _ui.update { it.copy(creating = false) }   // 别把按钮卡在"创建中"
                return@launch
            }
            runCatching {
                // 首条消息就带上模型 / 思维链选择（与对话页发送同口径）
                apiFactory.forOrigin(origin).startAgentSession(
                    AgentSessionStart(
                        content = text,
                        attachments = _ui.value.attachments.map { it.attachmentId },
                        model = _ui.value.modelRef ?: "",
                        thinkingLevel = _ui.value.thinkingLevel,
                    )
                ).dataOrThrow()
            }
                .onSuccess { accepted ->
                    // 建完清空：这个页面可能还在返回栈上，别留下上次的图和文字
                    _ui.update { it.copy(creating = false, attachments = emptyList(), input = "") }
                    // 会话列表立刻能看到这条（「我的」页订阅这个信号）
                    sessionRepository.notifyAgentSessionsChanged()
                    onCreated(accepted.sessionId)
                }
                .onFailure { e -> _ui.update { it.copy(creating = false, error = friendlyMessage(e)) } }
        }
    }
}

/** 新建会话:输入首条消息即开始(与网页端一致:消息与建会话同一请求) */
@Composable
fun AgentNewSessionScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    vm: AgentNewSessionViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp)) {
            TextButton(onClick = onBack) { Text("取消", color = TextMuted) }
            Text("新会话", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(16.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("你可以让我做什么", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "「帮我订阅《幕府将军》,只要 4K」",
                    "「看看现在有哪些任务在跑,失败的重试一下」",
                    "「这部剧缺了第 4 集,帮我找找资源」",
                    "「最近空间不够了,清理一下重复文件」",
                ).forEach { example ->
                    Text(example, fontSize = 14.sp, color = TextMuted, lineHeight = 21.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 图片附件（iOS 新会话页用的同一个 AgentComposer，`+` 菜单里就有图片）：
        // 选择器 + 已选图片条 + 工具行里的图片键，与对话页同一份口径
        val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        ) { uri -> uri?.let(vm::attachImage) }
        if (state.attachments.isNotEmpty() || state.uploading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                state.attachments.forEach { attachment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AccentSoft)
                            .clickable { vm.removeAttachment(attachment.attachmentId) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = Accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(attachment.name.take(18), fontSize = 11.sp, color = Accent)
                        Spacer(Modifier.width(5.dp))
                        Text("×", fontSize = 12.sp, color = Accent)
                    }
                }
                if (state.uploading) {
                    Text("上传中…", fontSize = 11.sp, color = TextFaint)
                }
            }
        }
        // 输入区与对话页同一套（iOS `AgentComposer` 结构）：容器里是输入框 + 底部工具行，
        // 工具行里放模型 chip 与发送键——两页观感一致，首条消息也能先选模型
        var showModelMenu by remember { mutableStateOf(false) }
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp)),
        ) {
            TextField(
                value = state.input,
                onValueChange = vm::onInput,
                placeholder = { Text("描述你的需求…", fontSize = 15.sp, color = TextFaint) },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.create(onCreated) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Accent,
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            )
                        )
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = "添加图片", tint = TextMuted, modifier = Modifier.size(20.dp))
                }
                if (state.models.isNotEmpty()) {
                    ModelChip(
                        label = AgentThinking.resolve(state.models, state.modelRef)?.label ?: "模型",
                        enabled = !state.creating,
                        onClick = { showModelMenu = true },
                    )
                }
                Spacer(Modifier.weight(1f))
                val canSend = !state.creating && !state.uploading &&
                    (state.input.isNotBlank() || state.attachments.isNotEmpty())
                IconButton(
                    onClick = { vm.create(onCreated) },
                    enabled = canSend,
                    modifier = Modifier.size(44.dp).background(
                        if (canSend) Accent else Color.White.copy(alpha = 0.08f),
                        CircleShape,
                    ),
                ) {
                    if (state.creating) {
                        CircularProgressIndicator(color = TextMuted, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "开始对话",
                            tint = Color(0xFF0A0E12),
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
        state.error?.let {
            Text(it, fontSize = 12.sp, color = Danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        Spacer(Modifier.height(6.dp))
        if (showModelMenu) {
            val menuOffset = with(androidx.compose.ui.platform.LocalDensity.current) {
                androidx.compose.ui.unit.IntOffset(16.dp.roundToPx(), (-210).dp.roundToPx())
            }
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopStart,
                offset = menuOffset,
                onDismissRequest = { showModelMenu = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                io.movieclaw.android.core.designsystem.MenuAppear(originX = 0.12f, originY = 1f) {
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = io.movieclaw.android.core.designsystem.MenuSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, io.movieclaw.android.core.designsystem.LineColor),
                        shadowElevation = 12.dp,
                    ) {
                        ModelMenu(
                            models = state.models,
                            modelRef = state.modelRef,
                            thinkingLevel = state.thinkingLevel,
                            onPickModel = { ref ->
                                vm.setModel(ref)
                                showModelMenu = false
                            },
                            onPickThinking = { level -> vm.setThinkingLevel(level) },
                        )
                    }
                }
            }
        }
    }
}
