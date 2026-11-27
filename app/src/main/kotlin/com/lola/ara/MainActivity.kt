package com.lola.ara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var authEmail by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var authName by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var authStatus by remember { mutableStateOf(if (apiToken.isBlank()) "Sign in or create an account to connect Ara." else "Neon Auth session found on this device.") }
    var cloudStatus by remember { mutableStateOf("Cloud connection not tested yet.") }
    var sending by remember { mutableStateOf(false) }
    var memory by remember { mutableStateOf(preferences.getString("memory_notes", "") ?: "") }
    var memorySyncing by remember { mutableStateOf(false) }
    var memorySyncStatus by remember { mutableStateOf("Cloud memory has not been synced.") }
    var workspaceFiles by remember { mutableStateOf(preferences.getStringSet("workspace_files", emptySet())?.toList() ?: emptyList()) }
    var workspaceStatus by remember { mutableStateOf("Imported files stay in this phone’s private app storage.") }
    var reviewStatus by remember { mutableStateOf("Files are private until you choose to send them to Ara for review.") }
    var personality by remember { mutableStateOf(preferences.getString("ara_personality", "Warm, capable, direct and respectful.") ?: "") }
    var skills by remember { mutableStateOf(preferences.getString("ara_skills", "Research, writing, coding assistance, file analysis") ?: "") }
    var instructions by remember { mutableStateOf(preferences.getString("ara_instructions", "Ask before external actions. Never make financial decisions or payments.") ?: "") }
    LaunchedEffect(personality) { preferences.edit().putString("ara_personality", personality).apply() }
    LaunchedEffect(skills) { preferences.edit().putString("ara_skills", skills).apply() }
    LaunchedEffect(instructions) { preferences.edit().putString("ara_instructions", instructions).apply() }
    LaunchedEffect(workspaceFiles) { preferences.edit().putStringSet("workspace_files", workspaceFiles.toSet()).apply() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var added = 0
        uris.forEach { uri ->
            try {
                val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                val displayName = cursor?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "uploaded-file"
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "uploaded-file" }
                val folder = File(context.filesDir, "developer-workspace").apply { mkdirs() }
                val destination = File(folder, System.currentTimeMillis().toString() + "_" + safeName)
                context.contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output -> input.copyTo(output, 8192) } }
                if (destination.exists() && destination.length() <= 5L * 1024 * 1024) { workspaceFiles = workspaceFiles + destination.name; added++ } else destination.delete()
            } catch (_: Exception) { }
        }
        workspaceStatus = if (added > 0) "$added file(s) imported." else "No files imported. Maximum size is 5 MB per file."
    }
    var showDeleteCloudConfirmation by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(preferences.getBoolean("paused", false)) }
    var requireApproval by remember { mutableStateOf(preferences.getBoolean("require_approval", true)) }
    var allowRoutineTasks by remember { mutableStateOf(preferences.getBoolean("allow_routine_tasks", false)) }
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
    LaunchedEffect(requireApproval) { preferences.edit().putBoolean("require_approval", requireApproval).apply() }
    LaunchedEffect(allowRoutineTasks) { preferences.edit().putBoolean("allow_routine_tasks", allowRoutineTasks).apply() }
    LaunchedEffect(messages) {
        preferences.edit().putString("chat_history", messages.joinToString(MESSAGE_SEPARATOR)).apply()
    }

    fun syncCloudMemory(action: String) {
        if (memorySyncing) return
        memorySyncing = true
        memorySyncStatus = when (action) { "save" -> "Saving notes to Ara cloud…"; "load" -> "Loading notes from Ara cloud…"; else -> "Deleting cloud notes…" }
        val notesToSave = memory
        Thread {
            try {
                val loaded = when (action) {
                    "save" -> { AraCloudClient.saveMemory(context, apiToken, notesToSave); null }
                    "load" -> AraCloudClient.loadMemory(context, apiToken)
                    else -> { AraCloudClient.deleteMemory(context, apiToken); null }
                }
                context.mainExecutor.execute {
                    if (action == "load") memory = loaded.orEmpty()
                    if (action == "delete") memorySyncStatus = "Cloud memory deleted. Local notes were kept."
                    else memorySyncStatus = if (action == "save") "Notes saved to Ara cloud." else "Cloud notes loaded onto this device."
                    memorySyncing = false
                }
            } catch (error: Exception) {
                context.mainExecutor.execute {
                    memorySyncStatus = error.message ?: "Cloud memory operation failed."
                    memorySyncing = false
                }
            }
        }.start()
    }
    val tabs = listOf("Chat", "Memory", "Autonomy", "Workspace", "Developer")
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
                1 -> Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Private memory", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text("Notes stay on this device unless you choose to sync them. Synced notes are stored on Ara’s server and are not automatically sent to Gemini.")
                    OutlinedTextField(
                        value = memory,
                        onValueChange = { memory = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        placeholder = { Text("Add notes Ara may use later…") },
                        minLines = 8
                    )
                    Button(onClick = { syncCloudMemory("save") }, enabled = !memorySyncing && apiToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (memorySyncing) "Please wait…" else "Save notes to cloud")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { syncCloudMemory("load") }, enabled = !memorySyncing && apiToken.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Load cloud notes") }
                        OutlinedButton(onClick = { showDeleteCloudConfirmation = true }, enabled = !memorySyncing && apiToken.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Delete cloud notes") }
                    }
                    OutlinedButton(onClick = { memory = "" }, modifier = Modifier.fillMaxWidth()) { Text("Clear local notes") }
                    Text(memorySyncStatus, style = MaterialTheme.typography.bodySmall)
                    if (showDeleteCloudConfirmation) {
                        AlertDialog(
                            onDismissRequest = { showDeleteCloudConfirmation = false },
                            title = { Text("Delete cloud memory?") },
                            text = { Text("This permanently deletes the notes stored for this cloud token. Your local notes on this phone will be kept.") },
                            confirmButton = { TextButton(onClick = { showDeleteCloudConfirmation = false; syncCloudMemory("delete") }) { Text("Delete") } },
                            dismissButton = { TextButton(onClick = { showDeleteCloudConfirmation = false }) { Text("Cancel") } }
                        )
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
                    Divider()
                    Text("Permission settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Require approval before changes")
                            Text("Ara may prepare proposals, but must not apply code changes without your approval.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = requireApproval, onCheckedChange = { requireApproval = it })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow routine, reversible tasks")
                            Text("This records your preference only; it does not start an agent or grant background permissions.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = allowRoutineTasks, onCheckedChange = { allowRoutineTasks = it })
                    }
                    if (!requireApproval) {
                        Text("Warning: approval is switched off in this local preference. Code editing and deployment are still not connected.", color = MaterialTheme.colorScheme.error)
                    }
                }
                3 -> Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Developer workspace", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text("Import code, documents and images for Ara to inspect. Files are stored privately on this phone.")
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Upload files") }
                    Text(workspaceStatus, style = MaterialTheme.typography.bodySmall)
                    Text(reviewStatus, style = MaterialTheme.typography.bodySmall)
                    if (workspaceFiles.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            try {
                                val folder = File(context.filesDir, "developer-workspace")
                                val allowed = setOf("kt", "java", "js", "mjs", "ts", "tsx", "jsx", "py", "md", "txt", "json", "xml", "gradle", "kts", "yml", "yaml", "html", "css", "sql")
                                var total = 0
                                val excerpts = workspaceFiles.mapNotNull { name ->
                                    val file = File(folder, name)
                                    val extension = name.substringAfterLast(".", "").lowercase()
                                    if (!file.isFile || extension !in allowed) return@mapNotNull null
                                    val remaining = 6500 - total
                                    if (remaining <= 0) return@mapNotNull null
                                    val content = file.readText(Charsets.UTF_8).take(remaining)
                                    total += content.length
                                    "FILE: $name\\n$content"
                                }
                                if (excerpts.isEmpty()) {
                                    reviewStatus = "No supported text/code files found. Images and binary files are kept locally but cannot be reviewed as source text yet."
                                } else {
                                    input = "Please review these uploaded project files and suggest improvements. Do not claim to edit or deploy anything. Return a proposed patch and explain the tests and risks.\\n\\n" + excerpts.joinToString("\\n\\n")
                                    tab = 0
                                    reviewStatus = "Review draft prepared in Chat. Check it before pressing Send; nothing has been sent yet."
                                }
                            } catch (error: Exception) {
                                reviewStatus = error.message ?: "Could not prepare the file review."
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FindInPage, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Prepare file review in Chat") }
                    }
                    if (workspaceFiles.isEmpty()) Text("No files imported yet.")
                    workspaceFiles.forEach { name ->
                        Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Pink)
                            Text(name, Modifier.weight(1f).padding(horizontal = 8.dp))
                            IconButton(onClick = { File(File(context.filesDir, "developer-workspace"), name).delete(); workspaceFiles = workspaceFiles - name }) { Icon(Icons.Default.Delete, contentDescription = "Remove file") }
                        } }
                    }
                    Text("File import is now available. Code review, patch approval and applying changes still need to be connected to Ara’s cloud development pipeline.", style = MaterialTheme.typography.bodySmall)
                }
                else -> Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Developer", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text("Ara configuration", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = personality, onValueChange = { personality = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Personality") }, minLines = 2)
                    OutlinedTextField(value = skills, onValueChange = { skills = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Skills") }, minLines = 2)
                    OutlinedTextField(value = instructions, onValueChange = { instructions = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Core instructions") }, minLines = 3)
                    Text("These settings are saved locally on this phone. They are not yet sent to the cloud model.", style = MaterialTheme.typography.bodySmall)
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Secure account connection", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Sign in with your Ara account. Neon Auth issues a short-lived signed token; the session cookie and token are encrypted using Android Keystore on this phone. Your password is not saved.")
                            OutlinedTextField(value = authEmail, onValueChange = { authEmail = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                            if (createAccount) {
                                OutlinedTextField(value = authName, onValueChange = { authName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
                            }
                            OutlinedTextField(value = authPassword, onValueChange = { authPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password (8+ characters)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { createAccount = false }, enabled = !authBusy, modifier = Modifier.weight(1f)) { Text("Sign in") }
                                Button(onClick = { createAccount = true }, enabled = !authBusy, modifier = Modifier.weight(1f)) { Text("Create account") }
                            }
                            Button(onClick = {
                                if (!authBusy) {
                                    authBusy = true
                                    authStatus = if (createAccount) "Creating your account…" else "Signing in securely…"
                                    val email = authEmail
                                    val password = authPassword
                                    val name = authName
                                    val signup = createAccount
                                    Thread {
                                        try {
                                            val token = AraCloudClient.authenticate(context, email, password, name, signup)
                                            context.mainExecutor.execute {
                                                apiToken = token
                                                authPassword = ""
                                                authStatus = "Connected securely to your Ara account."
                                                cloudStatus = "Neon Auth connected."
                                                authBusy = false
                                            }
                                        } catch (error: Exception) {
                                            context.mainExecutor.execute {
                                                authStatus = error.message ?: "Account connection failed."
                                                authBusy = false
                                            }
                                        }
                                    }.start()
                                }
                            }, enabled = !authBusy && authEmail.isNotBlank() && authPassword.length >= 8, modifier = Modifier.fillMaxWidth()) {
                                Text(if (authBusy) "Please wait…" else if (createAccount) "Create account and connect" else "Sign in and connect")
                            }
                            Text(authStatus)
                            if (apiToken.isNotBlank()) {
                                OutlinedButton(onClick = {
                                    if (!authBusy) {
                                        authBusy = true
                                        Thread {
                                            AraCloudClient.signOut(context)
                                            context.mainExecutor.execute {
                                                apiToken = ""
                                                authStatus = "Signed out on this device."
                                                cloudStatus = "Not connected."
                                                memorySyncStatus = "Sign in to sync cloud memory."
                                                authBusy = false
                                            }
                                        }.start()
                                    }
                                }, enabled = !authBusy, modifier = Modifier.fillMaxWidth()) { Text("Sign out on this device") }
                            }
                            Text("Gemini provider credentials remain on the server. This account controls access to its own cloud memory; signing out removes the saved local session.")
                        }
                    }
                }
            }
        }
    }
}
