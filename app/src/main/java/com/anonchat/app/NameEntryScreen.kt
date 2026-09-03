package com.anonchat.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonchat.app.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Экран первого запуска: пользователь придумывает имя, сервер выдаёт 5-значный ID. */
@Composable
fun NameEntryScreen(onRegistered: (Session) -> Unit) {
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Добро пожаловать в АнонЧат", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Придумайте имя — оно сохранится на этом устройстве", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Ваше имя") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        error = "Введите имя"
                        return@Button
                    }
                    loading = true
                    scope.launch {
                        try {
                            val session = withContext(Dispatchers.IO) { ApiClient.register(trimmed) }
                            loading = false
                            onRegistered(session)
                        } catch (e: Exception) {
                            loading = false
                            error = "Не удалось подключиться: ${e.message}"
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Подключение..." else "Начать общение")
            }
        }
    }
}
