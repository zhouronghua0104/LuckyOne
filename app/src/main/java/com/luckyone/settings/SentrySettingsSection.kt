package com.luckyone.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SentrySettingsSection(
    initialOutputFolderUseFileName: Boolean = true,
    initialOnlyExtractFrames: Boolean = false,
    onOutputFolderUseFileNameChanged: (Boolean) -> Unit = {},
    onOnlyExtractFramesChanged: (Boolean) -> Unit = {}
) {
    var outputFolderUseFileName by rememberSaveable {
        mutableStateOf(initialOutputFolderUseFileName)
    }
    var onlyExtractFrames by rememberSaveable {
        mutableStateOf(initialOnlyExtractFrames)
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "输出文件夹采用文件名")
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = outputFolderUseFileName,
                onCheckedChange = { checked ->
                    outputFolderUseFileName = checked
                    onOutputFolderUseFileNameChanged(checked)
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "只抽帧不推理")
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = onlyExtractFrames,
                onCheckedChange = { checked ->
                    onlyExtractFrames = checked
                    onOnlyExtractFramesChanged(checked)
                }
            )
        }
    }
}
