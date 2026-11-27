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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File
import java.util.zip.ZipInputStream

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
    val context = LocalContext.current
    val workspaceRoot = remember { File(context.filesDir, "workspace/AraAndroid") }
    var workspaceFiles by remember { mutableStateOf(emptyList<String>()) }
    var workspaceStatus by remember { mutableStateOf("No project imported yet.") }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            workspaceStatus = try {
                val count = importProjectZip(context, uri, workspaceRoot)
                workspaceFiles = listWorkspaceFiles(workspaceRoot)
                "Imported $count project files successfully."
            } catch (e: Exception) { "Import failed: ${e.message ?: "Unable to read ZIP"}" }
        }
    }
    LaunchedEffect(Unit) {
        workspaceFiles = listWorkspaceFiles(workspaceRoot)
        if (workspaceFiles.isNotEmpty()) workspaceStatus = "Workspace ready."
    }
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
                            if (tab == 3) {
                                Text("Project files are stored privately on this device.", fontSize = 16.sp)
                                Text(workspaceStatus, fontSize = 15.sp, color = Color.DarkGray)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { projectPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                        Text("Import project ZIP")
                                    }
                                    OutlinedButton(onClick = {
                                        workspaceFiles = listWorkspaceFiles(workspaceRoot)
                                        workspaceStatus = if (workspaceFiles.isEmpty()) "No project imported yet." else "Workspace refreshed."
                                    }) { Text("Refresh") }
                                }
                                Text("Workspace: files/workspace/AraAndroid", fontSize = 13.sp, color = Color.Gray)
                                Text("Files (${workspaceFiles.size})", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                if (workspaceFiles.isEmpty()) {
                                    Text("Choose the AraAndroid source ZIP to populate this workspace.", fontSize = 15.sp)
                                } else {
                                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        items(workspaceFiles) { path -> Text("•  $path", fontSize = 14.sp) }
                                    }
                                }
                            } else {
                                Text(
                                    if (tab == 1) "Your saved notes and preferences will appear here."
                                    else "Manage Ara's activity and automation settings here.",
                                    fontSize = 18.sp,
                                    lineHeight = 27.sp
                                )
                            }
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

private fun importProjectZip(context: android.content.Context, uri: Uri, destination: File): Int {
    destination.deleteRecursively()
    if (!destination.mkdirs() && !destination.isDirectory) error("Cannot create workspace folder")
    var extracted = 0
    val rootPath = destination.canonicalPath + File.separator
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.split("/").any { it == ".." }) error("ZIP contains an unsafe file path")
                val target = File(destination, name)
                if (target.canonicalPath != destination.canonicalPath && !target.canonicalPath.startsWith(rootPath)) {
                    error("ZIP entry is outside the workspace")
                }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                    extracted++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: error("Could not open the selected ZIP")
    if (extracted == 0) error("The ZIP contained no files")
    return extracted
}

private fun listWorkspaceFiles(root: File): List<String> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().take(300).toList()
}
