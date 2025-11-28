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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
    var expandedTagId by remember { mutableStateOf<Long?>(null) }
    val sampleFiles = listOf(
        "/sdcard/Download/history_trips.csv",
        "/sdcard/Documents/trips_2024.xlsx",
        "/storage/emulated/0/trips_backup.json"
    )
    val travelTagOptions = listOf("公司", "孩子学校", "健身房", "孩子培训班", "爱人公司")
    val travelHabits = remember {
        mutableStateListOf(
            TravelHabits(
                msgId = 1L,
                userId = 100001L,
                relationShip = "本人",
                travelTag = "公司",
                destination = "虹桥商务区 · 办公室",
                reachTime = "09:00"
            ),
            TravelHabits(
                msgId = 2L,
                userId = 100001L,
                relationShip = "孩子",
                travelTag = "孩子学校",
                destination = "上海枫叶学校",
                reachTime = "07:40"
            ),
            TravelHabits(
                msgId = 3L,
                userId = 100001L,
                relationShip = "爱人",
                travelTag = "爱人公司",
                destination = "浦东金融中心",
                reachTime = "08:30"
            )
        )
    }

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
                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { /* 打开文件选择器 */ }) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
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

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "个人地图专属",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "用户行程习惯",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (travelHabits.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF2F5F9),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "暂无行程习惯",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "拍照、上传或语音即可新增常去地点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    travelHabits.forEachIndexed { index, habit ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF5FAFB),
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "用户ID：${habit.userId}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "关系：${habit.relationShip.ifBlank { "未知" }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "行程${index + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = habit.travelTag.ifBlank { "未设置标签" },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    Box {
                                        IconButton(onClick = {
                                            expandedTagId = if (expandedTagId == habit.msgId) null else habit.msgId
                                        }) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = "修改行程标签"
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = expandedTagId == habit.msgId,
                                            onDismissRequest = { expandedTagId = null }
                                        ) {
                                            travelTagOptions.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        travelHabits[index] = habit.copy(travelTag = option)
                                                        expandedTagId = null
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            travelHabits.removeAt(index)
                                            if (expandedTagId == habit.msgId) expandedTagId = null
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "删除行程"
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = "删除")
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "你可以直接语音给我说“xxxx”“xxxx”来发起导航，去你常去的地方",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "目的地：${habit.destination}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "到达时间 ${habit.reachTime}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.secondary
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

interface ExclusiveMapActivity {
    fun importTripHistory(filePath: String)
}

data class TravelHabits(
    var msgId: Long = 0L,
    var createTime: Long = 0L,
    var userId: Long = 0L,
    var departure: String = "",
    var destination: String = "",
    var leaveTime: String = "",
    var reachTime: String = "",
    var relationShip: String = "",
    var travelTag: String = "",
    var associatedContacts: String = ""
)
