package com.streamcloud.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun NuvioProviderSettingsDialog(
    providerName: String,
    layoutJson: String,
    initialSettingsJson: String,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val layout = remember(layoutJson) {
        runCatching { Json.parseToJsonElement(layoutJson).jsonArray }
            .getOrElse { JsonArray(emptyList()) }
    }
    val currentSettings = remember(providerName, initialSettingsJson) {
        mutableStateMapOf<String, JsonElement>().apply {
            runCatching {
                Json.parseToJsonElement(initialSettingsJson).jsonObject
            }.getOrDefault(JsonObject(emptyMap())).forEach { (key, value) ->
                this[key] = value
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$providerName Settings") },
        text = {
            when {
                loading -> Text("Loading provider settings…")
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                layout.isEmpty() -> Text("This provider does not expose configurable settings.")
                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    layout.forEachIndexed { index, element ->
                        val field = element as? JsonObject ?: return@forEachIndexed
                        val type = field.string("type") ?: "info"
                        val key = field.string("key").orEmpty()
                        val label = field.string("label").orEmpty()
                        val description = field.string("description")

                        when (type) {
                            "header" -> Text(
                                label,
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            "info" -> Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            "text" -> {
                                if (key.isBlank()) return@forEachIndexed
                                val value = currentSettings[key].asSettingString().orEmpty()
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { currentSettings[key] = JsonPrimitive(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (field.bool("isPassword") == true) {
                                        PasswordVisualTransformation()
                                    } else {
                                        androidx.compose.ui.text.input.VisualTransformation.None
                                    },
                                )
                                description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            "select" -> {
                                if (key.isBlank()) return@forEachIndexed
                                val options = field["options"] as? JsonArray ?: JsonArray(emptyList())
                                val default = field.string("defaultValue").orEmpty()
                                val selected = currentSettings[key].asSettingString() ?: default
                                var expanded by remember(providerName, key, index) { mutableStateOf(false) }
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Box {
                                    OutlinedButton(
                                        onClick = { expanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        val selectedLabel = options.firstNotNullOfOrNull { optionElement ->
                                            val option = optionElement as? JsonObject ?: return@firstNotNullOfOrNull null
                                            if (option.string("value") == selected) option.string("label") else null
                                        }
                                        Text(selectedLabel ?: selected.ifBlank { "Select option" })
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        options.forEach { optionElement ->
                                            val option = optionElement as? JsonObject ?: return@forEach
                                            val optionValue = option.string("value").orEmpty()
                                            DropdownMenuItem(
                                                text = { Text(option.string("label") ?: optionValue) },
                                                onClick = {
                                                    currentSettings[key] = JsonPrimitive(optionValue)
                                                    expanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                                description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            "toggle" -> {
                                if (key.isBlank()) return@forEachIndexed
                                val checked = currentSettings[key].asSettingBoolean()
                                    ?: field.bool("defaultValue")
                                    ?: false
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.bodyLarge)
                                        description?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = checked,
                                        onCheckedChange = { currentSettings[key] = JsonPrimitive(it) },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(JsonObject(currentSettings.toMap()).toString()) },
                enabled = !loading && error == null,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.asSettingString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.asSettingBoolean(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull
