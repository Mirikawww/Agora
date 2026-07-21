package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPersonalizationPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val nickname by viewModel.settings.userProfileNickname.collectAsState()
    val gender by viewModel.settings.userProfileGender.collectAsState()
    val age by viewModel.settings.userProfileAge.collectAsState()
    val height by viewModel.settings.userProfileHeight.collectAsState()
    val occupation by viewModel.settings.userProfileOccupation.collectAsState()
    val other by viewModel.settings.userProfileOther.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_personalization),
        onBack = onBack
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.personalization_profile_title),
                items = listOf(
                    {
                        ProfileFieldCard(
                            icon = Icons.Default.Person,
                            label = stringResource(R.string.personalization_nickname),
                            value = nickname,
                            onChange = { viewModel.settings.setUserProfileNickname(it) },
                            placeholder = stringResource(R.string.personalization_nickname_hint)
                        )
                    },
                    {
                        ProfileFieldCard(
                            icon = Icons.Default.Person,
                            label = stringResource(R.string.personalization_gender),
                            value = gender,
                            onChange = { viewModel.settings.setUserProfileGender(it) },
                            placeholder = stringResource(R.string.personalization_gender_hint)
                        )
                    },
                    {
                        ProfileFieldCard(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.personalization_age),
                            value = age,
                            onChange = { viewModel.settings.setUserProfileAge(it) },
                            placeholder = stringResource(R.string.personalization_age_hint),
                            keyboard = KeyboardType.Number
                        )
                    },
                    {
                        ProfileFieldCard(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.personalization_height),
                            value = height,
                            onChange = { viewModel.settings.setUserProfileHeight(it) },
                            placeholder = stringResource(R.string.personalization_height_hint)
                        )
                    },
                    {
                        ProfileFieldCard(
                            icon = Icons.Default.Work,
                            label = stringResource(R.string.personalization_occupation),
                            value = occupation,
                            onChange = { viewModel.settings.setUserProfileOccupation(it) },
                            placeholder = stringResource(R.string.personalization_occupation_hint)
                        )
                    },
                    {
                        ProfileFieldCard(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.personalization_other),
                            value = other,
                            onChange = { viewModel.settings.setUserProfileOther(it) },
                            placeholder = stringResource(R.string.personalization_other_hint),
                            singleLine = false,
                            minLines = 3
                        )
                    }
                )
            )

            Text(
                text = stringResource(R.string.personalization_inject_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProfileFieldCard(
    icon: ImageVector,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    var draft by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != draft) draft = value }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onChange(it)
                },
                placeholder = placeholder?.let { ph ->
                    { Text(ph, style = MaterialTheme.typography.bodyMedium) }
                },
                singleLine = singleLine,
                minLines = minLines,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .noOpBringIntoView()
            )
        }
    }
}
