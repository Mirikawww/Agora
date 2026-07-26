package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SkillsMpClient
import com.newoether.agora.ui.components.CircularIconButton
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsSkillsStorePage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val installed by viewModel.skillsManager.skills.collectAsState()
    val skillsApiToken by viewModel.settings.skillsApiToken.collectAsState()
    val installedCatalogIds = remember(installed) {
        installed.mapNotNull { it.catalogId?.lowercase() }.toSet()
    }

    var tokenDraft by remember { mutableStateOf(skillsApiToken) }

    LaunchedEffect(skillsApiToken) {
        tokenDraft = skillsApiToken
        SkillsMpClient.apiToken = skillsApiToken
    }

    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<SkillsMpClient.CatalogSkill>>(emptyList()) }
    var searchMeta by remember { mutableStateOf<String?>(null) }

    var installingId by remember { mutableStateOf<String?>(null) }
    // Provider-style: String? navigation key only. Keep the last opened skill in
    // [retainedSkill] so the outgoing detail page still has data while animating back.
    var selectedSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    var retainedSkill by remember { mutableStateOf<SkillsMpClient.CatalogSkill?>(null) }
    // Hoist list scroll outside GuardedAnimatedContent so returning from detail
    // restores the previous offset (Provider does the same with scrollState).
    val listScrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }

    val loadFail = stringResource(R.string.skills_store_load_fail)
    val installOkTemplate = stringResource(R.string.skills_store_install_ok)
    val installFailTemplate = stringResource(R.string.skills_store_install_fail)

    fun openSkill(skill: SkillsMpClient.CatalogSkill) {
        retainedSkill = skill
        selectedSkillId = skill.id
    }

    fun loadPopular() {
        scope.launch {
            loading = true
            error = null
            searchMeta = null
            activeQuery = ""
            val result = SkillsMpClient.browsePopular(limit = 40)
            loading = false
            result.fold(
                onSuccess = { items = it },
                onFailure = {
                    items = emptyList()
                    error = it.message ?: loadFail
                },
            )
        }
    }

    fun runSearch(q: String) {
        val trimmed = q.trim()
        if (trimmed.length < 2) {
            loadPopular()
            return
        }
        scope.launch {
            loading = true
            error = null
            activeQuery = trimmed
            val result = SkillsMpClient.search(trimmed, limit = 40)
            loading = false
            result.fold(
                onSuccess = { sr ->
                    items = sr.skills
                    searchMeta = "${sr.count} · ${sr.durationMs} ms"
                },
                onFailure = {
                    items = emptyList()
                    searchMeta = null
                    error = it.message ?: loadFail
                },
            )
        }
    }

    fun refresh() {
        val trimmed = query.trim()
        if (trimmed.length >= 2) runSearch(trimmed) else loadPopular()
    }

    fun install(skill: SkillsMpClient.CatalogSkill) {
        scope.launch {
            installingId = skill.id
            val result = SkillsMpClient.install(skill)
            installingId = null
            result.fold(
                onSuccess = { content ->
                    viewModel.skillsManager.importMarkdown(
                        name = content.name,
                        content = content.content,
                        sourceFileName = content.sourceFileName,
                        catalogId = content.catalogId,
                        source = content.source,
                        pageUrl = content.pageUrl,
                    )
                    viewModel.emitSnackbar(String.format(installOkTemplate, content.name))
                },
                onFailure = {
                    viewModel.emitSnackbar(String.format(installFailTemplate, it.message ?: ""))
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        loadPopular()
    }

    // Exact Provider pattern: always consume back on this page so a detail pop
    // cannot fall through to SettingsScreen and tear down the whole store mid-animation.
    BackHandler {
        if (selectedSkillId != null) {
            selectedSkillId = null
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = selectedSkillId,
            forward = selectedSkillId != null,
        ) { skillId ->
            if (skillId != null) {
                val detail = retainedSkill?.takeIf { it.id == skillId }
                if (detail != null) {
                    SettingsSkillsStoreDetailPage(
                        skill = detail,
                        isInstalled = detail.id.lowercase() in installedCatalogIds,
                        busy = installingId == detail.id,
                        onBack = { selectedSkillId = null },
                        onInstall = { install(detail) },
                        onOpenWeb = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detail.url)))
                            }
                        },
                        onOpenGithub = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detail.githubUrl)))
                            }
                        },
                    )
                } else {
                    // Cache miss (e.g. process restore) — drop back to list cleanly.
                    LaunchedEffect(skillId) { selectedSkillId = null }
                }
            } else {
                CollapsingSettingsScaffold(
                    title = stringResource(R.string.skills_store_title),
                    onBack = onBack,
                    scrollState = listScrollState,
                    actions = {
                        CircularIconButton(
                            icon = Icons.Default.Refresh,
                            onClick = { refresh() },
                            contentDescription = stringResource(R.string.skills_store_refresh),
                            enabled = !loading,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                ) {
                    // Manual Column keeps search↔groups at 8.dp; default group spacing would inflate gaps.
                    CompositionLocalProvider(LocalSettingsGroupSpacing provides true) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SettingsGroup(
                                title = stringResource(R.string.skills_api_config),
                                items = listOf({
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        OutlinedTextField(
                                            value = tokenDraft,
                                            onValueChange = { tokenDraft = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { focusState ->
                                                    if (!focusState.isFocused) {
                                                        val trimmed = tokenDraft.trim()
                                                        if (trimmed != skillsApiToken) {
                                                            viewModel.settings.setSkillsApiToken(trimmed)
                                                        }
                                                    }
                                                },
                                            label = { Text(stringResource(R.string.skills_api_token_label)) },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        TextButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(SkillsMpClient.DOCS_URL))
                                                    )
                                                }
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 4.dp),
                                            )
                                            Text(stringResource(R.string.skills_register_token))
                                        }
                                    }
                                }),
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            StoreSearchBar(
                                query = query,
                                onQueryChange = { newQuery ->
                                    query = newQuery
                                    if (newQuery.trim().isEmpty() && activeQuery.isNotEmpty()) {
                                        loadPopular()
                                    }
                                },
                                onSearch = {
                                    keyboard?.hide()
                                    runSearch(query)
                                },
                            )
                            searchMeta?.let { meta ->
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            when {
                                loading && items.isEmpty() -> {
                                    SettingsGroup(
                                        title = "",
                                        items = listOf(
                                            {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(24.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                                }
                                            },
                                        ),
                                    )
                                }
                                error != null && items.isEmpty() -> {
                                    SettingsGroup(
                                        title = "",
                                        items = listOf(
                                            {
                                                StoreErrorBlock(
                                                    message = error ?: loadFail,
                                                    onRetry = { refresh() },
                                                )
                                            },
                                        ),
                                    )
                                }
                                items.isEmpty() -> {
                                    SettingsGroup(
                                        title = "",
                                        items = listOf(
                                            {
                                                SettingsItem(
                                                    headlineContent = { Text(stringResource(R.string.skills_store_empty)) },
                                                    supportingContent = {
                                                        Text(stringResource(R.string.skills_store_empty_desc))
                                                    },
                                                )
                                            },
                                        ),
                                    )
                                }
                                else -> {
                                    SettingsGroup(
                                        title = "",
                                        items = items.map { skill ->
                                            {
                                                val isInstalled = skill.id.lowercase() in installedCatalogIds
                                                val busy = installingId == skill.id
                                                StoreSkillRow(
                                                    skill = skill,
                                                    isInstalled = isInstalled,
                                                    busy = busy,
                                                    onClick = { openSkill(skill) },
                                                    onInstall = { install(skill) },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSkillsStoreDetailPage(
    skill: SkillsMpClient.CatalogSkill,
    isInstalled: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenGithub: () -> Unit,
) {
    CollapsingSettingsScaffold(
        title = skill.name,
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            // Install sits under the large title, above the first detail card.
            Button(
                onClick = onInstall,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isInstalled) {
                        stringResource(R.string.skills_store_reinstall)
                    } else {
                        stringResource(R.string.skills_store_install)
                    },
                )
            }

            // Description + metadata in one stitched group (no separate chips/info block).
            SettingsGroup(
                title = "",
                items = buildList {
                    add {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (isInstalled) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.skills_store_installed),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            Text(
                                text = skill.description.ifBlank {
                                    stringResource(R.string.skills_store_detail_no_desc)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (skill.description.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.skills_store_detail_author)) },
                            supportingContent = {
                                Text(skill.author.ifBlank { "—" })
                            },
                            leadingContent = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            },
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.skills_store_stars_label)) },
                            supportingContent = {
                                Text(stringResource(R.string.skills_store_stars, formatCount(skill.stars)))
                            },
                            leadingContent = {
                                Icon(Icons.Default.Star, contentDescription = null)
                            },
                        )
                    }
                    if (skill.contentLanguage.isNotBlank()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.skills_store_detail_language)) },
                                supportingContent = { Text(skill.contentLanguage) },
                                leadingContent = {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                },
                            )
                        }
                    }
                    if (skill.updatedAt > 0L) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.skills_store_detail_updated)) },
                                supportingContent = {
                                    Text(formatUpdatedAt(skill.updatedAt))
                                },
                            )
                        }
                    }
                },
            )

            SettingsGroup(
                title = stringResource(R.string.skills_store_detail_links),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.skills_store_open_web)) },
                            supportingContent = { Text(stringResource(R.string.skills_store_detail_open_page_desc)) },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            },
                            modifier = Modifier.clickable(onClick = onOpenWeb),
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.skills_store_open_github)) },
                            supportingContent = {
                                Text(
                                    skill.githubUrl,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Code, contentDescription = null)
                            },
                            modifier = Modifier.clickable(onClick = onOpenGithub),
                        )
                    },
                ),
            )
        }
    }
}

@Composable
private fun StoreSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_store_search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreErrorBlock(
    message: String,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.skills_store_retry))
        }
    }
}

@Composable
private fun StoreSkillRow(
    skill: SkillsMpClient.CatalogSkill,
    isInstalled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isInstalled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.skills_store_installed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = skill.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (skill.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.skills_store_stars, formatCount(skill.stars)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(
            onClick = onInstall,
            enabled = !busy,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = if (isInstalled) {
                    stringResource(R.string.skills_store_reinstall)
                } else {
                    stringResource(R.string.skills_store_install)
                },
            )
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatUpdatedAt(epochSeconds: Long): String {
    val millis = if (epochSeconds > 10_000_000_000L) epochSeconds else epochSeconds * 1000L
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}
