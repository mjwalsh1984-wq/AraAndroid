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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

private val Pink = Color(0xFFE91E8C)
private val PalePink = Color(0xFFFFE4F1)
private const val MESSAGE_SEPARATOR = "\u001E"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AraApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AraApp() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("ara_private_preferences", android.content.Context.MODE_PRIVATE)
    }
    var tab by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf(AraCloudClient.loadToken(context)) }
    var cloudStatus by remember { mutableStateOf("Cloud connection not tested yet.") }
    var sending by remember { mutableStateOf(false) }
    var memory by remember { mutableStateOf(preferences.getString("memory_notes", "") ?: "") }
    var paused by remember { mutableStateOf(preferences.getBoolean("paused", false)) }
    var messages by remember {
        mutableStateOf(
            preferences.getString("chat_history", null)
                ?.split(MESSAGE_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("Ara is ready to be configured. This starter does not connect to an AI service yet.")
        )
    }

    LaunchedEffect(memory) { preferences.edit().putString("memory_notes", memory).apply() }
    LaunchedEffect(paused) { preferences.edit().putBoolean("paused", paused).apply() }
    LaunchedEffect(messages) {
        preferences.edit().putString("chat_history", messages.joinToString(MESSAGE_SEPARATOR)).apply()
    }

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
                            Text("Messages are sent to Ara's private cloud API for Gemini replies. Keep sensitive information out of test chats.")
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
                            val question = input.trim()
                            if (question.isNotBlank() && !sending) {
                                val prior = messages.mapNotNull { line ->
                                    when {
                                        line.startsWith("You: ") -> "user" to line.removePrefix("You: ")
                                        line.startsWith("Ara: ") -> "assistant" to line.removePrefix("Ara: ")
                                        else -> null
                                    }
                                }
                                messages = messages.filterNot { it.startsWith("Ara: Message saved locally.") } + "You: $question"
                                input = ""
                                sending = true
                                cloudStatus = "Sending to Gemini…"
                                Thread {
                                    try {
                                        val reply = AraCloudClient.send(context, apiToken, prior + ("user" to question))
                                        context.mainExecutor.execute {
                                            messages = messages + "Ara: $reply"
                                            cloudStatus = "Gemini connected."
                                            sending = false
                                        }
                                    } catch (error: Exception) {
                                        context.mainExecutor.execute {
                                            messages = messages + "Ara: I couldn't reach Gemini. Check the cloud token and connection in Developer."
                                            cloudStatus = error.message ?: "Connection failed."
                                            sending = false
                                        }
                                    }
                                }.start()
                            }
                        }, containerColor = Pink) { Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White) }
                    }
                }
                1 -> Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Private memory", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text("Notes are saved locally on this device. They are not sent to an AI provider.")
                    OutlinedTextField(
                        value = memory,
                        onValueChange = { memory = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        placeholder = { Text("Add notes Ara may use later…") },
                        minLines = 8
                    )
                    OutlinedButton(onClick = { memory = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear local notes")
                    }
                }
                2 -> Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Autonomy", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (paused) "Ara is paused." else "Local controls are active.", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("This switch stores your pause preference. It does not start a background agent or cloud worker. Routine reversible tasks can be autonomous when connected; sensitive actions require approval.")
                        }
                    }
                    Button(onClick = { paused = !paused }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (paused) "Resume local controls" else "Pause local controls")
                    }
                }
                else -> Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Developer", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Gemini cloud connection", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Enter the Ara app token supplied during secure setup. It is encrypted with Android Keystore on this device; the Gemini provider key stays on the server.")
                            OutlinedTextField(
                                value = apiToken,
                                onValueChange = { apiToken = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Ara cloud app token") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Button(onClick = {
                                AraCloudClient.saveToken(context, apiToken.trim())
                                cloudStatus = "Cloud token saved securely on this device."
                            }, modifier = Modifier.fillMaxWidth()) { Text("Save cloud token") }
                            Text(cloudStatus)
                            Text("The cloud task runtime and persistent memory are not yet connected. Sensitive actions remain approval-gated; the emergency stop stays owner-controlled.")
                        }
                    }
                }
            }
        }
    }
}
