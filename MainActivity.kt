package com.lola.ara

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private val Pink = Color(0xFFE91E8C)
private val PalePink = Color(0xFFFFE4F1)

// image is either an absolute file path (a copy in private storage) or a URI string
private data class ChatMessage(val text: String, val image: String? = null, val fromUser: Boolean = true)

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
    var memories by remember { mutableStateOf(emptyList<String>()) }
    var memoryInput by remember { mutableStateOf("") }
    var dataLoaded by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var modelName by remember { mutableStateOf("gpt-4o-mini") }
    var settingsStatus by remember { mutableStateOf("") }
    val context = LocalContext.current
    val workspaceRoot = remember { File(context.filesDir, "workspace/AraAndroid") }
    var workspaceFiles by remember { mutableStateOf(emptyList<String>()) }
    var workspaceStatus by remember { mutableStateOf("No project imported yet.") }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorStatus by remember { mutableStateOf("Choose a file to open it here.") }
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
        // Load persisted chat, memories and settings before enabling saving,
        // so the empty initial state never overwrites what is on disk.
        try { messages = loadChatMessages(context) } catch (e: Exception) { }
        try { memories = loadMemories(context) } catch (e: Exception) { }
        paused = context.getSharedPreferences("ara_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("paused", false)
        val settingsPrefs = context.getSharedPreferences("ara_settings", android.content.Context.MODE_PRIVATE)
        apiKey = settingsPrefs.getString("api_key", "") ?: ""
        baseUrl = settingsPrefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        modelName = settingsPrefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini"
        if (!workspaceRoot.exists() || listWorkspaceFiles(workspaceRoot).isEmpty()) {
            try { copyBundledWorkspace(context, "developer_workspace", workspaceRoot) }
            catch (e: Exception) { workspaceStatus = "Workspace setup: ${e.message ?: "could not copy starter files"}" }
        }
        workspaceFiles = listWorkspaceFiles(workspaceRoot)
        if (workspaceFiles.isNotEmpty()) workspaceStatus = "Starter project ready on this device."
        dataLoaded = true
    }
    LaunchedEffect(messages) {
        if (dataLoaded) try { saveChatMessages(context, messages) } catch (e: Exception) { }
    }
    LaunchedEffect(memories) {
        if (dataLoaded) try { saveMemories(context, memories) } catch (e: Exception) { }
    }
    LaunchedEffect(paused) {
        if (dataLoaded) {
            context.getSharedPreferences("ara_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("paused", paused).apply()
        }
    }
    val tabs = listOf("Chat", "Memory", "Autonomy", "Developer", "Settings")
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
                                    3 -> Icons.Default.Code
                                    else -> Icons.Default.Settings
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
                    if (messages.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                messages = emptyList()
                                try { File(context.filesDir, "chat_images").deleteRecursively() } catch (e: Exception) { }
                            }) { Text("Clear chat", color = Pink) }
                        }
                    }
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
                                        message.image?.let { image ->
                                            AsyncImage(
                                                model = if (image.startsWith("/")) File(image) else image,
                                                contentDescription = "Attached image",
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (isThinking) {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    Card(
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1F1))
                                    ) {
                                        Text("Ara is thinking…", Modifier.padding(14.dp), fontSize = 17.sp, color = Color.DarkGray)
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
                                if ((input.isNotBlank() || selectedImage != null) && !isThinking) {
                                    val imagePath = selectedImage?.let { copyAttachedImage(context, it) }
                                    val updated = messages + ChatMessage(input.trim(), imagePath, true)
                                    messages = updated
                                    input = ""
                                    selectedImage = null
                                    isThinking = true
                                    requestAraReply(context, updated, memories) { reply ->
                                        messages = messages + ChatMessage(reply, null, false)
                                        isThinking = false
                                    }
                                }
                            },
                            containerColor = Pink
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                        }
                    }
                }
                1 -> Column(
                    Modifier.padding(pad).fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Memory", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text(
                        "Notes Ara should remember. They are saved on this device and are still here when you reopen the app.",
                        fontSize = 16.sp, lineHeight = 23.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = memoryInput,
                            onValueChange = { memoryInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Something to remember…") },
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (memoryInput.isNotBlank()) {
                                memories = memories + memoryInput.trim()
                                memoryInput = ""
                            }
                        }) { Text("Add") }
                    }
                    if (memories.isEmpty()) {
                        Text("No memories saved yet.", fontSize = 16.sp, color = Color.Gray)
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(memories) { note ->
                                Card(shape = RoundedCornerShape(16.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(note, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp)
                                        IconButton(onClick = { memories = memories - note }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Forget this", tint = Pink)
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { memories = emptyList() }) {
                            Text("Forget everything", color = Pink)
                        }
                    }
                }
                4 -> Column(
                    Modifier.padding(pad).fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text(
                        "Connect Ara to an AI service. OpenAI works out of the box, and so does any OpenAI-compatible service (for example OpenRouter or Groq) — just change the base URL and model. Your key is stored only on this device.",
                        fontSize = 16.sp, lineHeight = 23.sp
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        placeholder = { Text("sk-…") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Button(onClick = {
                        context.getSharedPreferences("ara_settings", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putString("api_key", apiKey.trim())
                            .putString("base_url", baseUrl.trim())
                            .putString("model", modelName.trim())
                            .apply()
                        settingsStatus = "Saved. Ara will use these settings for her next reply."
                    }) { Text("Save settings") }
                    if (settingsStatus.isNotBlank()) {
                        Text(settingsStatus, fontSize = 15.sp, color = Color.DarkGray)
                    }
                    Text(
                        "No key yet? Ara still replies in Chat with a note explaining she isn't connected, and everything you type is saved as before.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = Color.Gray
                    )
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
                                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        items(workspaceFiles) { path ->
                                            TextButton(
                                                onClick = {
                                                    selectedFile = path
                                                    editorText = try {
                                                        File(workspaceRoot, path).readText()
                                                    } catch (e: Exception) {
                                                        editorStatus = "Could not open file: ${e.message ?: "unknown error"}"
                                                        ""
                                                    }
                                                    if (editorText.isNotEmpty() || File(workspaceRoot, path).length() == 0L) {
                                                        editorStatus = "Opened $path"
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(path, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
                                            }
                                        }
                                    }
                                    if (selectedFile != null) {
                                        HorizontalDivider()
                                        Text("File editor", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text(selectedFile ?: "", fontSize = 12.sp, color = Color.Gray)
                                        OutlinedTextField(
                                            value = editorText,
                                            onValueChange = { editorText = it },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp
                                            ),
                                            placeholder = { Text("File contents") }
                                        )
                                        Text(editorStatus, fontSize = 12.sp, color = Color.DarkGray)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = {
                                                try {
                                                    val target = File(workspaceRoot, selectedFile!!)
                                                    val root = workspaceRoot.canonicalPath + File.separator
                                                    require(target.canonicalPath.startsWith(root)) { "Invalid file path" }
                                                    target.parentFile?.mkdirs()
                                                    target.writeText(editorText)
                                                    editorStatus = "Saved $selectedFile"
                                                    workspaceFiles = listWorkspaceFiles(workspaceRoot)
                                                } catch (e: Exception) {
                                                    editorStatus = "Save failed: ${e.message ?: "unknown error"}"
                                                }
                                            }) { Text("Save file") }
                                            OutlinedButton(onClick = {
                                                selectedFile = null
                                                editorText = ""
                                                editorStatus = "Choose a file to open it here."
                                            }) { Text("Close file") }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    "Manage Ara's activity and automation settings here. Your Pause choice is now remembered between launches.",
                                    fontSize = 18.sp,
                                    lineHeight = 27.sp
                                )
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

// ---------- Persistence (chat + memory, stored as JSON in private files) ----------

private fun chatFile(context: android.content.Context) = File(context.filesDir, "ara_chat.json")

private fun memoryFile(context: android.content.Context) = File(context.filesDir, "ara_memory.json")

private fun saveChatMessages(context: android.content.Context, messages: List<ChatMessage>) {
    val array = JSONArray()
    messages.forEach { message ->
        array.put(JSONObject().apply {
            put("text", message.text)
            put("image", message.image ?: JSONObject.NULL)
            put("fromUser", message.fromUser)
        })
    }
    chatFile(context).writeText(array.toString())
}

private fun loadChatMessages(context: android.content.Context): List<ChatMessage> {
    val file = chatFile(context)
    if (!file.exists()) return emptyList()
    val array = JSONArray(file.readText())
    val result = mutableListOf<ChatMessage>()
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        result.add(
            ChatMessage(
                text = item.optString("text", ""),
                image = if (item.isNull("image")) null else item.optString("image").ifBlank { null },
                fromUser = item.optBoolean("fromUser", true)
            )
        )
    }
    return result
}

private fun saveMemories(context: android.content.Context, memories: List<String>) {
    val array = JSONArray()
    memories.forEach { array.put(it) }
    memoryFile(context).writeText(array.toString())
}

private fun loadMemories(context: android.content.Context): List<String> {
    val file = memoryFile(context)
    if (!file.exists()) return emptyList()
    val array = JSONArray(file.readText())
    val result = mutableListOf<String>()
    for (i in 0 until array.length()) result.add(array.getString(i))
    return result
}

// Copy a picked image into private storage so it survives the photo picker's
// temporary URI permission and is still there after the app is reopened.
// Falls back to the original URI string if the copy fails.
private fun copyAttachedImage(context: android.content.Context, uri: Uri): String {
    return try {
        val dir = File(context.filesDir, "chat_images")
        dir.mkdirs()
        val target = File(dir, "img_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return uri.toString()
        target.absolutePath
    } catch (e: Exception) {
        uri.toString()
    }
}

// ---------- AI replies (OpenAI-compatible chat completions) ----------

// Sends the recent conversation to the configured service and calls onResult
// on the main thread with Ara's reply (or a friendly error / offline note).
// Attached images are not sent to the service in this version.
private fun requestAraReply(
    context: android.content.Context,
    history: List<ChatMessage>,
    memories: List<String>,
    onResult: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val prefs = context.getSharedPreferences("ara_settings", android.content.Context.MODE_PRIVATE)
    val key = (prefs.getString("api_key", "") ?: "").trim()
    if (key.isBlank()) {
        val memoryNote = if (memories.isEmpty()) {
            "You haven't saved anything in my Memory tab yet."
        } else {
            "I can see ${memories.size} saved ${if (memories.size == 1) "memory" else "memories"}, and I'll use them once I'm connected."
        }
        mainHandler.post {
            onResult(
                "I'm not connected to an AI service yet, so I can't properly reply. " +
                    "Open the Settings tab and add an API key (OpenAI or any OpenAI-compatible service), then ask me again. " +
                    memoryNote + " Everything you type is still being saved."
            )
        }
        return
    }
    val base = (prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1")
        .trim().trimEnd('/')
    val model = (prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini").trim()
    Thread {
        val reply = try {
            val systemPrompt = buildString {
                append("You are Ara, a warm, helpful personal AI assistant inside an Android app. ")
                append("Be friendly, clear and fairly concise.")
                if (memories.isNotEmpty()) {
                    append("\n\nThings the user has asked you to remember:")
                    memories.forEach { append("\n- ").append(it) }
                }
            }
            val messagesJson = JSONArray()
            messagesJson.put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.takeLast(20).filter { it.text.isNotBlank() }.forEach { message ->
                messagesJson.put(
                    JSONObject()
                        .put("role", if (message.fromUser) "user" else "assistant")
                        .put("content", message.text)
                )
            }
            val body = JSONObject().put("model", model).put("messages", messagesJson).toString()
            val connection = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                JSONObject(responseText)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                    .ifBlank { "I got an empty reply from the AI service — please try again." }
            } else {
                "Sorry, the AI service returned HTTP $code. Check your API key, model and base URL in the Settings tab, then try again."
            }
        } catch (e: Exception) {
            "Sorry, I couldn't reach the AI service (${e.message ?: "network error"}). Check your connection and the Settings tab, then try again."
        }
        mainHandler.post { onResult(reply) }
    }.start()
}

// ---------- Developer workspace ----------

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

private fun copyBundledWorkspace(context: android.content.Context, assetPath: String, destination: File) {
    val children = context.assets.list(assetPath).orEmpty()
    if (children.isEmpty()) {
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    } else {
        destination.mkdirs()
        children.forEach { child -> copyBundledWorkspace(context, "$assetPath/$child", File(destination, child)) }
    }
}
