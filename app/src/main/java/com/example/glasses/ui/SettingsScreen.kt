package com.example.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.glasses.ui.theme.AppBlue
import com.example.glasses.ui.theme.AppMutedText
import com.example.glasses.ui.theme.AppOutline

@Composable
internal fun SettingsScreen(
    settings: GlassesSettings,
    onSettingsChanged: (GlassesSettings) -> Unit,
    onBack: () -> Unit,
    onOpenSoundscapeTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        SettingsTopBar(onBack)
        HorizontalDivider(color = AppOutline)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            SettingsSectionTitle("音频")
            SettingIconLabel(Icons.AutoMirrored.Filled.VolumeUp, "声景音量")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = settings.outputVolume,
                    onValueChange = {
                        onSettingsChanged(settings.copy(outputVolume = it))
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(settings.outputVolume * 100).toInt()}%",
                    color = AppMutedText,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            HorizontalDivider(color = AppOutline, modifier = Modifier.padding(vertical = 10.dp))
            SettingsSectionTitle("显示")
            ToggleSettingRow(
                icon = Icons.Default.GridOn,
                title = "显示辅助网格",
                subtitle = "在实时画面上叠加 64 × 64 网格",
                checked = settings.showGrid,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(showGrid = it))
                },
            )

            HorizontalDivider(color = AppOutline, modifier = Modifier.padding(vertical = 10.dp))
            SettingsSectionTitle("检测")
            InformationRow(
                icon = Icons.Default.Memory,
                title = "深度模型",
                value = "YOLO26 Depth",
            )
            InformationRow(
                icon = Icons.Default.PhoneAndroid,
                title = "运行设备",
                value = "本机摄像头",
            )

            HorizontalDivider(color = AppOutline, modifier = Modifier.padding(vertical = 10.dp))
            SettingsSectionTitle("工具")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSoundscapeTest)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = AppBlue,
                    modifier = Modifier.size(22.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text("声景测试", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "查看左右与上下空间映射页面",
                        color = AppMutedText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = AppMutedText,
                )
            }

            HorizontalDivider(color = AppOutline, modifier = Modifier.padding(vertical = 10.dp))
            SettingsSectionTitle("系统")
            InformationRow(title = "应用版本", value = "1.0.0")
            InformationRow(title = "界面模式", value = "辅助视觉")
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SettingIconLabel(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AppBlue, modifier = Modifier.size(21.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun ToggleSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppBlue, modifier = Modifier.size(21.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = AppMutedText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InformationRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AppBlue, modifier = Modifier.size(21.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = AppMutedText, style = MaterialTheme.typography.bodyMedium)
    }
}
