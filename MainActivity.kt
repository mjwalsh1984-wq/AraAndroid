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
    var messages by remember { mutableStateOf(listOf("Ara is ready to be configured. This starter does not connect to an AI service yet.")) }
    val tabs = listOf("Chat", "Memory", "Autonomy", "Developer")
    MaterialTheme(colorScheme = lightColorScheme(primary = Pink, secondary = Color(0xFFAD1457), background = Color(0xFFFFF8FB))) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Column {
                    Text("ara", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text("YOUR PERSONAL AI ASSISTANT", fontSize = 9.sp, letterSpacing = 1.5.sp)
                } }, actions = {
                    AssistChip(onClick = { paused = !paused }, label = { Text(if (paused) "Paused" else "Active") })
                    Spacer(Modifier.width(10.dp))
                })
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, name ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Icon(when(i) { 0 -> Icons.Default.Chat; 1 -> Icons.Default.Bookmark; 2 -> Icons.Default.AutoAwesome; else -> Icons.Default.Code }, contentDescription = name) },
                            label = { Text(name) })
                    }
                }
            }
        ) { pad ->
            when (tab) {
                0 -> Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = PalePink), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Hello, Lola 💗", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("This is Ara's first app shell. Her AI brain and integrations still need connecting.")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages) { line -> Card { Text(line, Modifier.padding(14.dp)) } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message Ara…") }, maxLines = 3)
                        Spacer(Modifier.width(8.dp))
                        FloatingActionButton(onClick = {
                            if (input.isNotBlank()) {
                                messages = messages + "You: $input" + "Ara: Message received. Connect an AI provider for real conversations."
                                input = ""
                            }
                        }, containerColor = Pink) { Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White) }
                    }
                }
                else -> Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(tabs[tab], fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when(tab) {
                                1 -> "Persistent memory is not connected yet."
                                2 -> if (paused) "Ara is paused." else "Autonomy is not configured yet."
                                else -> "Self-development is not enabled yet."
                            }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(when(tab) {
                                1 -> "A future version can store notes and preferences privately on this device."
                                2 -> "Routine reversible tasks can be autonomous; sensitive actions require approval. Finances and emergency controls stay with you."
                                else -> "A future developer workspace can propose changes, run tests, keep version history and roll back. It must not rewrite safety controls."
                            })
                        }
                    }
                }
            }
        }
    }
}
