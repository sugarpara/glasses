package com.example.glasses.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.glasses.R
import com.example.glasses.ui.theme.AppBlue
import com.example.glasses.ui.theme.AppGreen
import com.example.glasses.ui.theme.AppNavy
import com.example.glasses.ui.theme.AppOrange
import com.example.glasses.ui.theme.AppPurple

private enum class AppScreen {
    HOME,
    ASSISTANCE,
    SETTINGS,
    SOUNDSCAPE_TEST,
}

@Composable
fun GlassesApp() {
    val view = LocalView.current
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var settings by remember { mutableStateOf(GlassesSettings()) }

    fun updateSettings(updated: GlassesSettings) {
        settings = updated
    }

    DisposableEffect(screen, view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val useDarkIcons = screen != AppScreen.HOME
        controller.isAppearanceLightStatusBars = useDarkIcons
        controller.isAppearanceLightNavigationBars = useDarkIcons
        onDispose { }
    }

    BackHandler(enabled = screen != AppScreen.HOME) {
        screen = AppScreen.HOME
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            onStartAssistance = { screen = AppScreen.ASSISTANCE },
            onOpenSettings = { screen = AppScreen.SETTINGS },
            onOpenSoundscapeTest = { screen = AppScreen.SOUNDSCAPE_TEST },
        )

        AppScreen.ASSISTANCE -> DepthCameraScreen(
            settings = settings,
            onBack = { screen = AppScreen.HOME },
        )

        AppScreen.SETTINGS -> SettingsScreen(
            settings = settings,
            onSettingsChanged = ::updateSettings,
            onBack = { screen = AppScreen.HOME },
            onOpenSoundscapeTest = { screen = AppScreen.SOUNDSCAPE_TEST },
        )

        AppScreen.SOUNDSCAPE_TEST -> SoundscapeTestScreen(
            onBack = { screen = AppScreen.HOME },
        )
    }
}

@Composable
private fun HomeScreen(
    onStartAssistance: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSoundscapeTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppNavy)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Default.Menu, contentDescription = "菜单", tint = Color.White)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.Info, contentDescription = "关于", tint = Color.White)
            }
        }

        Text(
            text = "Hello!",
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "辅助设备状态",
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(AppGreen),
            )
            Text(
                text = "本机摄像头可用",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Image(
            painter = painterResource(R.drawable.smart_glasses_product),
            contentDescription = "智能眼镜",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .aspectRatio(2.74f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeatureTile(
                title = "开始辅助",
                icon = Icons.Default.PlayCircle,
                color = AppBlue,
                onClick = onStartAssistance,
                modifier = Modifier.weight(1f),
            )
            FeatureTile(
                title = "路线导航",
                icon = Icons.Default.Navigation,
                color = AppPurple,
                enabled = false,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeatureTile(
                title = "环境描述",
                icon = Icons.Default.RecordVoiceOver,
                color = AppOrange,
                enabled = false,
                modifier = Modifier.weight(1f),
            )
            FeatureTile(
                title = "设置",
                icon = Icons.Default.Settings,
                color = AppGreen,
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }

        Surface(
            color = Color.White.copy(alpha = 0.08f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clickable(onClick = onOpenSoundscapeTest),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = Color.White,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = "声景测试",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "检查左右与上下空间定位",
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Glasses Assistant v1.0.0",
            color = Color.White.copy(alpha = 0.48f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FeatureTile(
    title: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Surface(
        color = if (enabled) color else color.copy(alpha = 0.42f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .height(94.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(31.dp),
            )
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 7.dp),
            )
            if (!enabled) {
                Text(
                    text = "暂未接入",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
