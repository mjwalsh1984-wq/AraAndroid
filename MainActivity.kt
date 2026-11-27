package com.lola.ara

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import coil.compose.AsyncImage

private val Pink = Color(0xFFE91E8C)
private val PalePink = Color(0xFFFFE4F1)

private data class ChatMessage(val text: String, val image: Uri? = null, val fromUser: Boolean = true)

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
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }
    val tabs = listOf("Chat", "Memory", "Autonomy", "Developer")
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> selectedImage = uri }

    MaterialTheme(colorScheme = lightColorScheme(primary = Pink, secondary = Color(0xFFAD1457), background = Color(0xFFFFF8FB))) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, name ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {
                                Icon(when (i) {
                                    0 -> Icons.Default.Chat
                                    1 -> Icons.Default.Bookmark
                                    2 -> Icons.Default.AutoAwesome
                                    else -> Icons.Default.Code
                                }, contentDescription = name)
                            },
                            label = { Text(name, fontSize = 12.sp) }
                        )
                    }
                }
            }
        ) { pad ->
            when (tab) {
                0 -> Column(
                    Modifier.padding(pad).fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                                    Text("Start a conversation", fontSize = 20.sp, color = Color.Gray)
                                }
                            }
                        }
                        items(messages) { message ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(0.94f),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (message.fromUser) PalePink else Color(0xFFF1F1F1)
                                    )
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (message.text.isNotBlank()) {
                                            SelectionContainer {
                                                Text(message.text, fontSize = 18.sp, lineHeight = 26.sp)
                                            }
                                        }
                                        message.image?.let { uri ->
                                            AsyncImage(
                                                model = uri,
                                                contentDescription = "Attached image",
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    selectedImage?.let { uri ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                AsyncImage(model = uri, contentDescription = "Image ready to attach", modifier = Modifier.size(64.dp))
                                Text("Image ready to attach", Modifier.weight(1f).padding(horizontal = 10.dp))
                                IconButton(onClick = { selectedImage = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove image")
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Attach image", tint = Pink)
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message Ara…", fontSize = 17.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
                            minLines = 1,
                            maxLines = 5,
                            shape = RoundedCornerShape(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        FloatingActionButton(
                            onClick = {
                                if (input.isNotBlank() || selectedImage != null) {
                                    messages = messages + ChatMessage(input.trim(), selectedImage, true)
                                    input = ""
                                    selectedImage = null
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
                            if (tab == 2) {
                                Button(onClick = { paused = !paused }) {
                                    Text(if (paused) "Resume" else "Pause")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
