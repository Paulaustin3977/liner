package dev.liner.launcher.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one-time prompt that asks the user to make Liner their home app.
 *
 * It is shown on the first launch, while Liner is not the default home app,
 * and it goes away for good once the user dismisses it — or once Liner *is*
 * the default home app.
 */
@Composable
fun FirstRunPrompt(
    onSetAsDefault: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Set Liner as your home app", style = MaterialTheme.typography.titleMedium)
            Text(
                "Liner is not your default launcher yet. Choose it to use it as your home screen.",
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = onSetAsDefault) {
                    Text("Set as default")
                }
                TextButton(onClick = onDismiss) {
                    Text("Not now")
                }
            }
        }
    }
}

/**
 * Whether the first-run prompt belongs on the screen.
 *
 * It needs three things to be true, and only two of them are known at
 * first: the user must not have dismissed it, and Liner must *genuinely*
 * not be the default home app. The third — that the document has been read —
 * is checked by the caller, because the answer is a property of the
 * document, not of this condition.
 *
 * [isDefaultHome] is null while the answer is still unknown: the check is a
 * binder round trip, and a prompt that flashed in and out while it was
 * pending would be worse than no prompt at all.
 *
 * The condition is "is not the default home app" and not merely "has not
 * been dismissed": the prompt must not come back once Liner *is* the default
 * home app, however the user got there.
 */
internal fun shouldShowFirstRunPrompt(
    dismissed: Boolean,
    isDefaultHome: Boolean?,
): Boolean = !dismissed && isDefaultHome == false
