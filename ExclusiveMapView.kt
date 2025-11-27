import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun ExclusiveMapView(
    exclusiveMapActivity: ExclusiveMapActivity,
    name: String,
    modifier: Modifier
) {
    val scrollState = rememberScrollState()
    var importFilePath by rememberSaveable { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val sampleFiles = listOf(
        "/sdcard/Download/history_trips.csv",
        "/sdcard/Documents/trips_2024.xlsx",
        "/storage/emulated/0/trips_backup.json"
    )

    Column(modifier = modifier.verticalScroll(scrollState)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xFFE1F5FE), shape = RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "导入历史行程记录",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "选择需要导入的文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sampleFiles.forEach { sample ->
                    AssistChip(
                        onClick = {
                            importFilePath = sample
                            importMessage = null
                        },
                        label = { Text(text = sample.substringAfterLast('/')) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color.White,
                            labelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = importFilePath,
                onValueChange = {
                    importFilePath = it
                    importMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = "例如：/sdcard/Download/trips_2024.csv") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { /* 打开文件选择器 */ }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    }
                },
                supportingText = { Text(text = "目前支持 CSV / XLSX / JSON 文件") },
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (importFilePath.isNotBlank() && !isImporting) {
                        importMessage = null
                        isImporting = true
                        runCatching { exclusiveMapActivity.importTripHistory(importFilePath) }
                            .onSuccess { importMessage = "导入成功" }
                            .onFailure { importMessage = it.message ?: "导入失败" }
                        isImporting = false
                    }
                })
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    importMessage = null
                    isImporting = true
                    runCatching { exclusiveMapActivity.importTripHistory(importFilePath) }
                        .onSuccess { importMessage = "导入成功" }
                        .onFailure { importMessage = it.message ?: "导入失败" }
                    isImporting = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = importFilePath.isNotBlank() && !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
                Text(text = if (isImporting) "正在导入..." else "开始导入")
            }

            importMessage?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    color = if (message == "导入成功") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { /* 可选：跳转到导入帮助 */ }) {
                Text(text = "查看导入说明")
            }
        }
    }
}

interface ExclusiveMapActivity {
    fun importTripHistory(filePath: String)
}
