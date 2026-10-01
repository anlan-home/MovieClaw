package io.movieclaw.android.feature.search

import io.movieclaw.android.core.designsystem.LocalFeedback
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Close
import io.movieclaw.android.core.designsystem.AccentStrong
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.PaletteSurface
import io.movieclaw.android.core.designsystem.TextPrimary
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.AccentSoft
import io.movieclaw.android.core.designsystem.Danger
import io.movieclaw.android.core.designsystem.GlassCard
import io.movieclaw.android.core.designsystem.Info
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.Success
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.Warning
import io.movieclaw.android.core.model.TorrentCategory
import io.movieclaw.android.core.model.TorrentHit

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenLibraryItem: (Long, Long) -> Unit,
    onSubscribe: (String) -> Unit,
    onOpenTitle: (String) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val origin = vm.origin
    val keyboard = LocalSoftwareKeyboardController.current
    var pendingDownload by remember { mutableStateOf<TorrentHit?>(null) }

    val feedback = LocalFeedback.current

    LaunchedEffect(state.notice) {
        state.notice?.let {
            feedback.show(it)
            vm.consumeNotice()
        }
    }

    // 全屏命令面板（实测：底色 rgba(15,17,23,.94)，URL 不变）
    Box(Modifier.fillMaxSize().background(PaletteSurface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 头部：输入框 312×40、圆角 999、白 9%；右侧「取消」17px
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = McMetrics.pagePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.09f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = TextFaint, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (state.query.isEmpty()) {
                            Text(placeholderFor(state.mode), style = McType.body, color = TextFaint, maxLines = 1)
                        }
                        BasicTextField(
                            value = state.query,
                            onValueChange = vm::onQuery,
                            singleLine = true,
                            textStyle = McType.body.copy(color = Color.White),
                            cursorBrush = Brush.verticalGradient(listOf(AccentStrong, AccentStrong)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.submit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.searching) {
                        CircularProgressIndicator(color = TextMuted, modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    } else if (state.query.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = TextFaint,
                            modifier = Modifier.size(16.dp).clickable { vm.onQuery("") },
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "取消",
                    style = McType.headline.copy(fontWeight = FontWeight.Normal),
                    color = TextPrimary,
                    modifier = Modifier.clickable { keyboard?.hide(); onBack() },
                )
            }

            SearchModeSelector(current = state.mode, onSelect = vm::onMode)

            if (state.mode == SearchMode.TORRENTS) {
                CategoryChips(selected = state.category, onSelect = vm::onCategory)
            }

            if (state.history.isNotEmpty() && state.blocks.isEmpty() && state.titles.isEmpty() && state.libraryGroups.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最近搜索", style = McType.headline)
                    Spacer(Modifier.weight(1f))
                    Text("清空", style = McType.caption, color = TextFaint, modifier = Modifier.clickable { vm.clearHistory() })
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.history, key = { it.id }) { item ->
                        Text(
                            item.keyword,
                            style = McType.sub,
                            color = TextPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                                .clickable { vm.submit(item.keyword) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    fontSize = 12.sp,
                    color = Danger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (state.mode == SearchMode.TORRENTS) {
                    items(state.blocks, key = { it.siteId }) { block ->
                        SiteSection(block = block, submittingId = state.submitting, onDownload = { pendingDownload = it })
                    }
                    state.done?.let { done ->
                        item {
                            Text(
                                "共 ${done.total} 条 · ${done.elapsedMs} ms · ${done.sites.size} 个站点",
                                fontSize = 11.5.sp,
                                color = TextFaint,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                if (state.mode == SearchMode.TITLES) {
                    // iOS MediaSearchResultsView：豆瓣 / TMDB 两栏并排，各自带来源标签与条数
                    val douban = state.titles.filter { it.provider.equals("douban", true) }
                    val tmdb = state.titles.filterNot { it.provider.equals("douban", true) }
                    item(key = "titles-dual") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = McMetrics.pagePadding, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            listOf("豆瓣" to douban, "TMDB" to tmdb).forEach { (label, list) ->
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        label,
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF9FB0C9),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(Color.White.copy(alpha = 0.06f))
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                    Text("共 ${list.size} 条结果", fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                                    list.forEach { title ->
                                        TitleRow(
                                            title = title,
                                            origin = origin,
                                            onOpen = { onOpenTitle(title.titleRef) },
                                            onSubscribe = { onSubscribe(title.titleRef) },
                                        )
                                    }
                                    if (list.isEmpty()) Text("没有结果", fontSize = 12.sp, color = TextFaint)
                                }
                            }
                        }
                    }
                }

                if (state.mode == SearchMode.LIBRARY) {
                    state.libraryGroups.forEach { (libraryName, items) ->
                        item(key = "header-$libraryName") {
                            Text(
                                "$libraryName · ${items.size}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(items, key = { "lib-${it.mediaItemId}" }) { item ->
                            LibraryResultRow(
                                title = item.title,
                                year = item.year,
                                posterUrl = item.posterUrl,
                                origin = origin,
                                onClick = { onOpenLibraryItem(item.libraryId ?: -1L, item.mediaItemId) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDownload?.let { hit ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("提交下载") },
            text = {
                Column {
                    Text(hit.title, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(hit.siteName, hit.size, "${hit.seeders} 做种").joinToString(" · "),
                        fontSize = 11.5.sp,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("将按种子名称自动识别身份并投递到匹配的媒体库。", fontSize = 11.5.sp, color = TextFaint)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.submitDownload(hit)
                    pendingDownload = null
                }) { Text("下载", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SearchModeSelector(current: SearchMode, onSelect: (SearchMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = McMetrics.pagePadding)
            .height(37.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        SearchMode.entries.forEach { mode ->
            val on = mode == current
            Box(
                Modifier
                    .weight(1f)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Color.White.copy(alpha = 0.13f) else Color.Transparent)
                    .clickable { onSelect(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (mode) {
                        SearchMode.TITLES -> "影视"
                        SearchMode.TORRENTS -> "资源"
                        SearchMode.LIBRARY -> "媒体库"
                    },
                    style = McType.subSemibold,
                    color = if (on) TextPrimary else TextMuted,
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(selected: TorrentCategory?, onSelect: (TorrentCategory?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentSoft,
                    selectedLabelColor = Accent,
                ),
            )
        }
        items(TorrentCategory.entries.toList()) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(category.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentSoft,
                    selectedLabelColor = Accent,
                ),
            )
        }
    }
}

@Composable
private fun SiteSection(block: SiteBlock, submittingId: String?, onDownload: (TorrentHit) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(block.siteName.ifEmpty { block.siteId }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            when {
                block.error != null -> Text("失败:${block.error}", fontSize = 11.sp, color = Danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                block.items.isNotEmpty() -> Text("${block.items.size} 条 · ${block.elapsedMs} ms", fontSize = 11.sp, color = TextFaint)
                else -> Text("搜索中…", fontSize = 11.sp, color = TextFaint)
            }
        }
        if (block.error == null && block.items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                CircularProgressIndicator(color = TextMuted, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        block.items.take(30).forEach { hit ->
            TorrentRow(hit = hit, submitting = submittingId == hit.torrentId, onDownload = { onDownload(hit) })
        }
    }
}

@Composable
private fun TorrentRow(hit: TorrentHit, submitting: Boolean, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !submitting, onClick = onDownload)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                hit.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                hit.size?.let { Text(it, fontSize = 11.sp, color = TextFaint) }
                Text("↑${hit.seeders}", fontSize = 11.sp, color = if (hit.seeders > 0) Success else TextFaint)
                Text("↓${hit.leechers}", fontSize = 11.sp, color = TextFaint)
                if (hit.free || hit.downloadVolumeFactor == 0f) {
                    Text(
                        "免费",
                        fontSize = 10.sp,
                        color = Success,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Success.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                hit.category?.let { category ->
                    TorrentCategory.of(category)?.let {
                        Text(it.label, fontSize = 10.sp, color = Info)
                    }
                }
            }
        }
        if (submitting) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun TitleRow(
    title: io.movieclaw.android.core.model.DiscoveredTitle,
    origin: String?,
    onOpen: () -> Unit,
    onSubscribe: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(8.dp))) {
            RemoteImage(url = title.posterUrl, origin = origin, contentDescription = title.title, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    title.releaseYear?.toString(),
                    title.providerRating.takeIf { it > 0f }?.let { "★ ${"%.1f".format(it)}" },
                    title.extentLabel.takeIf { it.isNotEmpty() },
                    title.provider.uppercase().takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = TextFaint,
            )
            if (title.overview.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(title.overview, fontSize = 11.5.sp, color = TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("查看详情 ›", fontSize = 11.sp, color = TextMuted)
                Spacer(Modifier.width(12.dp))
                Text(
                    "订阅",
                    fontSize = 11.sp,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(AccentSoft)
                        .clickable(onClick = onSubscribe)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryResultRow(
    title: String,
    year: Int?,
    posterUrl: String?,
    origin: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp).height(66.dp).clip(RoundedCornerShape(8.dp))) {
            RemoteImage(url = posterUrl, origin = origin, contentDescription = title, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            year?.let { Text(it.toString(), fontSize = 11.sp, color = TextFaint) }
        }
    }
}

@Composable
internal fun GlassNotice(text: String) {
    GlassCard(Modifier.padding(horizontal = 16.dp)) {
        Text(text, fontSize = 12.5.sp, color = Warning)
    }
}

private fun placeholderFor(mode: SearchMode): String = when (mode) {
    SearchMode.TORRENTS -> "片名 / 关键词 / IMDb ID"
    SearchMode.TITLES -> "搜索影视标题"
    SearchMode.LIBRARY -> "搜索已入库的影片…"
}
