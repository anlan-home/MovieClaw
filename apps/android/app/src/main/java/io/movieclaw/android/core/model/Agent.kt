package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val lastPrompt: String? = null,
    val entryCount: Int = 0,
    val running: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class AgentSessionStart(
    val content: String,
    val sessionId: String? = null,
    val attachments: List<String> = emptyList(),
    val model: String = "",
    val thinkingLevel: String? = null,
)

@Serializable
data class SessionAccepted(val sessionId: String, val messageId: String = "")

/**
 * 待处理事项 → Agent 诊断工单（`POST /agent-handoff`）。
 *
 * 工单正文由**服务端**组装（发生了什么 / 现场自检判定了什么 / 系统已自动做过什么 /
 * 与界面一致的动作清单），前端不拼：Agent 能看到的证据必须比用户多，否则它只会
 * 得出和用户一样的错误结论。
 */
@Serializable
data class HandoffRequest(
    /** notice（ref=告警 id）/ download（ref=info_hash）/ job（ref=任务 id） */
    val kind: String,
    val ref: String,
)

@Serializable
data class HandoffPrompt(
    val title: String = "",
    val prompt: String = "",
)

@Serializable
data class AgentAttachment(
    val attachmentId: String,
    val name: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
)

@Serializable
data class ToolCallInfo(
    val id: String = "",
    val name: String = "",
    /**
     * 工具调用参数：服务端是**已解析的对象**（`movieclaw_llm.models.ToolCall.arguments: dict`），
     * iOS 侧对应 `arguments: AgentJSONObject?`。声明成 JsonElement 而不是 String——
     * 以前声明 String 时，带工具调用的历史会话一加载就整条失败
     * （kotlinx：Expected beginning of the string, but got {）。
     * JsonElement 对对象/数组/字符串都收，比 iOS 更宽容（老数据里若有字符串化参数也不炸）。
     */
    val arguments: kotlinx.serialization.json.JsonElement? = null,
    /** 供应商原始 JSON 串（服务端 `raw_arguments`；解析失败时保真展示用） */
    val rawArguments: String? = null,
    /** 参数 JSON 解析失败的原因（服务端 `parse_error`） */
    val parseError: String? = null,
)

@Serializable
data class AgentToolResultInfo(
    val toolCallId: String = "",
    val name: String = "",
    val output: String = "",
    val isError: Boolean = false,
    val elapsedMs: Long = 0,
)

@Serializable
data class AgentCompactionInfo(
    val summary: String = "",
    val tokensBefore: Int = 0,
    val tokensAfter: Int = 0,
)

@Serializable
data class AgentDoneInfo(
    val text: String? = null,
    val thinking: String? = null,
    val steps: Int = 1,
    val model: String = "",
    val provider: String = "",
    val elapsedMs: Long = 0,
)

/**
 * Agent SSE 事件:type 即 SSE 事件名。字段按类型有值——
 * agent_start / thinking_delta / text_delta / tool_call_start / tool_call_delta /
 * tool_call / tool_result / context_compacted / agent_done / agent_error / agent_cancelled。
 */
@Serializable
data class AgentEvent(
    val type: String = "",
    val runId: String = "",
    val delta: String? = null,
    val toolCall: ToolCallInfo? = null,
    val toolCallId: String? = null,
    val toolResult: AgentToolResultInfo? = null,
    val compaction: AgentCompactionInfo? = null,
    val provider: String? = null,
    val model: String? = null,
    val result: AgentDoneInfo? = null,
    val error: String? = null,
)

@Serializable
data class SessionMessage(
    val role: String = "user",
    /** str | list[ContentPart],原样保留后由客户端抽文本 */
    val content: JsonElement? = null,
    val toolCalls: List<ToolCallInfo>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

/** 轨迹条目(message / compaction / handoff 三型联合,按出现字段判别) */
@Serializable
data class TranscriptEntry(
    val messageId: String? = null,
    val compactionId: String? = null,
    val handoffId: String? = null,
    val timestamp: String? = null,
    val message: SessionMessage? = null,
    val summary: String? = null,
    val tokensBefore: Int? = null,
    val tokensAfter: Int? = null,
    val model: String? = null,
    val sourceTitle: String? = null,
)

@Serializable
data class SessionTranscript(
    val session: SessionSummary = SessionSummary(id = ""),
    val entries: List<TranscriptEntry> = emptyList(),
)

@Serializable
data class SessionRename(val title: String)

@Serializable
data class SessionRetry(
    /** 要重新提问的 user message 编号（服务端会删掉它及其之后的轨迹） */
    val messageId: String? = null,
    /** 替换后的正文；留空 = 原文重试（`POST /sessions/{id}/retry`） */
    val content: String? = null,
)

/** 对话框模型选择器的一个选项（`GET /llm/models`，口径同 iOS `API.LlmModelOptionView`） */
@Serializable
data class LlmModelOption(
    /** 提交给 session.start 的模型引用：裸模型 id，或同 id 多实例时的「实例名/模型id」 */
    val ref: String = "",
    /** 展示文案：裸模型 id，冲突时为「模型id（实例名）」 */
    val label: String = "",
    val modelId: String? = null,
    val providerId: Int? = null,
    val providerName: String? = null,
    /** 是否为智能体默认模型（AI 设定）；清单里恰有一个 */
    val isDefault: Boolean = false,
    /** 该模型的思考档位菜单；空 = 隐藏档位选择 */
    val thinkingLevels: List<String> = emptyList(),
)

/** 一个可显式调用的 Agent 技能（`GET /skills`）；气泡里的 `/skill:名字` chip 用它做门禁 */
@Serializable
data class SkillView(
    val name: String = "",
    val description: String? = null,
    val scope: String? = null,
)
