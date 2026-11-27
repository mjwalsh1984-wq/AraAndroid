package com.lola.ara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Pink = Color(0xFFE91E8C)
private val PalePink = Color(0xFFFFE4F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AraApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AraApp() {
    var tab by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(emptyList<String>()) }
    val tabs = listOf("Chat", "Memory", "Autonomy", "Developer")

    MaterialTheme(colorScheme = lightColorScheme(primary = Pink, secondary = Color(0xFFAD1457), background = Color(0xFFFFF8FB))) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("ara", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pink) },
                    actions = {
                        AssistChip(
                            onClick = { paused = !paused },
                            label = { Text(if (paused) "Paused" else "Active", fontSize = 14.sp) }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, name ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {
                                Icon(
                                    when (i) {
                                        0 -> Icons.Default.Chat
                                        1 -> Icons.Default.Bookmark
                                        2 -> Icons.Default.AutoAwesome
                                        else -> Icons.Default.Code
                                    },
                                    contentDescription = name
                                )
                            },
                            label = { Text(name, fontSize = 12.sp) }
                        )
                    }
                }
            }
        ) { pad ->
            when (tab) {
                0 -> Column(
                    Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
                                    Text("Start a conversation", fontSize = 20.sp, color = Color.Gray)
                                }
                            }
                        }
                        items(messages) { line ->
                            val isUser = line.startsWith("You:")
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(0.92f),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) PalePink else Color(0xFFF1F1F1)
                                    )
                                ) {
                                    Text(
                                        line.removePrefix("You: ").removePrefix("Ara: "),
                                        Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                        fontSize = 19.sp,
                                        lineHeight = 27.sp
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message Ara…", fontSize = 17.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
                            minLines = 2,
                            maxLines = 5,
                            shape = RoundedCornerShape(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        FloatingActionButton(
                            onClick = {
                                if (input.isNotBlank()) {
                                    messages = messages + "You: $input" + "Ara: Message received. Connect an AI provider for real conversations."
                                    input = ""
                                }
                            },
                            containerColor = Pink
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                        }
                    }
                }
                else -> Column(
                    Modifier.padding(pad).fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(tabs[tab], fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                when (tab) {
                                    1 -> "Memory"
                                    2 -> if (paused) "Paused" else "Autonomy"
                                    else -> "Developer workspace"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 21.sp
                            )
                            Text(
                                when (tab) {
                                    1 -> "Your saved notes and preferences will appear here."
                                    2 -> "Manage Ara's activity and automation settings here."
                                    else -> "Development tools and version information will appear here."
                                },
                                fontSize = 18.sp,
                                lineHeight = 27.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
