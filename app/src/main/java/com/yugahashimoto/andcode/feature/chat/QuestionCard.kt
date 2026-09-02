package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

@Composable
fun QuestionCard(
    question: PendingQuestionUi,
    onAnswerSelected: (String, Int, String) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: (String) -> Unit = {},
    onDismiss: (String) -> Unit = {},
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("question-${question.request.id}"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
            ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Closing the card leaves the turn running, so the question can simply be ignored and
            // a normal message typed instead.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = { onDismiss(question.request.id) },
                    enabled = !question.isSubmitting,
                    modifier =
                        Modifier
                            .size(28.dp)
                            .testTag("question-dismiss-${question.request.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.question_dismiss),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            question.request.questions.forEachIndexed { index, prompt ->
                val selectedAnswers = question.selectedAnswers.getOrElse(index) { emptyList() }
                val optionLabels = prompt.options.map { it.label }.toSet()
                val fallbackText = selectedAnswers.lastOrNull { it !in optionLabels }.orEmpty()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.header?.takeIf { it.isNotBlank() }?.let { header ->
                        Text(
                            text = header,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = prompt.question,
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    prompt.options.forEach { option ->
                        val selected = option.label in selectedAnswers
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onAnswerSelected(question.request.id, index, option.label) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (prompt.multiple) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { onAnswerSelected(question.request.id, index, option.label) },
                                )
                            } else {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onAnswerSelected(question.request.id, index, option.label) },
                                )
                            }
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                option.description?.takeIf { it.isNotBlank() }?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Offered alongside the options too, because the presented choices are not
                    // always exhaustive. `custom` is how OpenCode says a prompt only accepts its
                    // own options, so a typed answer is dropped when it is off.
                    if (prompt.options.isEmpty() || prompt.custom) {
                        if (prompt.options.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.question_free_text_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = fallbackText,
                            onValueChange = { onAnswerSelected(question.request.id, index, it) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("question-free-text-${question.request.id}-$index"),
                            placeholder = {
                                Text(prompt.placeholder ?: stringResource(R.string.message_hint))
                            },
                            singleLine = !prompt.multiple,
                        )
                    }
                }
            }

            question.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onCancel(question.request.id) },
                    enabled = !question.isSubmitting,
                    modifier = Modifier.testTag("question-cancel-${question.request.id}"),
                ) {
                    Text(stringResource(R.string.question_cancel))
                }
                Button(
                    onClick = { onSubmit(question.request.id) },
                    enabled = question.canSubmit && !question.isSubmitting,
                    modifier = Modifier.testTag("question-submit-${question.request.id}"),
                ) {
                    if (question.isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.continue_label))
                    }
                }
            }
        }
    }
}
