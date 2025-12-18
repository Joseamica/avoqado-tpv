# Compose Keyboard Handling - Dialog TextField Issues

**Last Updated:** 2025-12-17
**Issue Type:** Android Jetpack Compose UX Pattern
**Severity:** High (blocks user from completing actions)

---

## Problem Summary

When using `TextField` inside `AlertDialog` in Jetpack Compose, the Android software keyboard cannot be dismissed properly, causing:

1. **Enter button makes line breaks** instead of triggering `onDone` action
2. **Keyboard covers action buttons** (Enviar, Cancelar) making them unreachable
3. **No way to hide keyboard** - user is stuck

### Visual Example

```
┌─────────────────────────┐
│   Reportar Bug          │
│ ┌─────────────────────┐ │
│ │ User message here   │ │
│ │                     │ │
│ └─────────────────────┘ │
├─────────────────────────┤
│   [Android Keyboard]    │ ← Covers buttons!
│   q w e r t y u i o p   │
│    a s d f g h j k l    │
│     z x c v b n m       │
│   [  Yugo  ] [  UGT  ]  │
└─────────────────────────┘
                             ← Enviar/Cancelar buttons unreachable!
```

---

## Root Cause

The issue has **multiple causes**:

### 1. `AlertDialog` has its own `LocalFocusManager`
   - You MUST capture `LocalFocusManager.current` **INSIDE** the dialog context
   - Capturing it outside the dialog won't work

### 2. `keyboardController?.hide()` alone is insufficient
   - Only hides keyboard visually
   - Doesn't clear focus from TextField
   - Keyboard can reappear when TextField is touched again

### 3. `decorFitsSystemWindows` property issues
   - Setting `decorFitsSystemWindows = false` in `DialogProperties` has limited effect
   - Dialog always insets within system bars
   - `WindowInsets` are often zero in Dialog context

### 4. Default `ImeAction.Default` doesn't hide keyboard
   - Enter button only inserts newlines
   - No built-in hide behavior

---

## Solution (Complete Pattern)

### Step 1: Use Custom `Dialog` instead of `AlertDialog`

`AlertDialog` has limitations with keyboard management. Use `Dialog` from `androidx.compose.ui.window`:

```kotlin
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
        usePlatformDefaultWidth = false,  // Allow custom width
        decorFitsSystemWindows = false     // Attempt better keyboard handling
    )
) {
    // Content here
}
```

### Step 2: Capture `LocalFocusManager` INSIDE Dialog

**❌ WRONG:**
```kotlin
@Composable
fun MyScreen() {
    val focusManager = LocalFocusManager.current  // ← Captured OUTSIDE dialog

    Dialog(...) {
        TextField(
            // This won't work correctly
        )
    }
}
```

**✅ CORRECT:**
```kotlin
@Composable
fun MyScreen() {
    Dialog(...) {
        val focusManager = LocalFocusManager.current  // ← Captured INSIDE dialog

        TextField(
            // This works correctly
        )
    }
}
```

### Step 3: Add `.imePadding()` to Container

This modifier handles IME (Input Method Editor = keyboard) insets:

```kotlin
Dialog(...) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .fillMaxHeight(0.85f)  // Leave space for keyboard
            .imePadding()  // ← CRITICAL: Adds padding when keyboard appears
    ) {
        // Content
    }
}
```

### Step 4: Make Content Scrollable

Allow user to scroll to see buttons even when keyboard is visible:

```kotlin
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())  // ← Makes content scrollable
        .padding(24.dp)
) {
    // Icon, Title, TextField, Buttons
}
```

### Step 5: Add Tap-Outside-to-Dismiss

Use `pointerInput` with `detectTapGestures` to clear focus when tapping outside TextField:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(24.dp)
        .pointerInput(Unit) {  // ← Detects taps on empty space
            detectTapGestures(onTap = {
                focusManager.clearFocus()
            })
        }
) {
    // Content
}
```

### Step 6: Configure Keyboard Actions

Change Enter key to "Done" (✔) button and clear focus on press:

```kotlin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

OutlinedTextField(
    value = message,
    onValueChange = { message = it },
    keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Done  // ← Changes Enter to Done button
    ),
    keyboardActions = KeyboardActions(
        onDone = {
            focusManager.clearFocus()  // ← Clears focus (hides keyboard)
        }
    ),
    maxLines = 10,
    singleLine = false
)
```

### Step 7: Clear Focus on Button Click

Ensure keyboard is hidden when user presses action buttons:

```kotlin
Button(
    onClick = {
        focusManager.clearFocus()  // ← Hide keyboard before action
        onSend(message)
    },
    enabled = message.isNotBlank()
) {
    Text("Enviar")
}
```

---

## Complete Working Example

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun FeedbackDialog(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    onDismiss: () -> Unit,
    onSend: (message: String) -> Unit
) {
    var message by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // ✅ STEP 2: Capture FocusManager INSIDE Dialog
        val focusManager = LocalFocusManager.current

        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .imePadding(),  // ✅ STEP 3: IME padding
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())  // ✅ STEP 4: Scrollable
                    .padding(24.dp)
                    .pointerInput(Unit) {  // ✅ STEP 5: Tap outside to dismiss
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                        })
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon + Title
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(40.dp)
                    )

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Subtitle
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // TextField
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Describe el problema o sugerencia...") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done  // ✅ STEP 6: Done button
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()  // ✅ Clear focus on Done
                        }
                    ),
                    maxLines = 15,
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()  // ✅ STEP 7: Clear on button
                            onSend(message)
                        },
                        enabled = message.isNotBlank()
                    ) {
                        Text("Enviar")
                    }
                }
            }
        }
    }
}
```

---

## How It Works

### Three Ways to Dismiss Keyboard:

1. **Press "Done" button (✔)** on keyboard
   - `ImeAction.Done` changes Enter key to Done
   - `KeyboardActions.onDone` calls `focusManager.clearFocus()`

2. **Tap anywhere outside TextField**
   - `pointerInput` + `detectTapGestures` detects tap
   - Calls `focusManager.clearFocus()`

3. **Press "Enviar" button**
   - Button `onClick` calls `focusManager.clearFocus()` first
   - Then executes action

### Why `focusManager.clearFocus()` instead of `keyboardController?.hide()`?

According to [research sources](https://canopas.com/keyboard-handling-in-jetpack-compose-all-you-need-to-know-3e6fddd30d9a):

- **`clearFocus()`**: Hides keyboard **AND** clears focus from TextField
- **`hide()`**: Only hides keyboard visually, focus remains on TextField

Using `clearFocus()` is more robust because:
- Keyboard won't reappear when view recomposes
- TextField loses focus state properly
- Works better in Dialog contexts

---

## Common Mistakes to Avoid

### ❌ Mistake 1: Capturing FocusManager Outside Dialog
```kotlin
@Composable
fun MyScreen() {
    val focusManager = LocalFocusManager.current  // ← WRONG!

    if (showDialog) {
        AlertDialog(...) {
            // focusManager here refers to PARENT context, not dialog
        }
    }
}
```

### ❌ Mistake 2: Using `keyboardController?.hide()` Only
```kotlin
KeyboardActions(
    onDone = {
        keyboardController?.hide()  // ← WRONG! Focus remains
    }
)
```

### ❌ Mistake 3: Non-Scrollable Content
```kotlin
Column(
    // ← WRONG! No verticalScroll modifier
) {
    TextField(...)
    Button(...)  // ← Unreachable when keyboard appears
}
```

### ❌ Mistake 4: Not Using `.imePadding()`
```kotlin
Card(
    // ← WRONG! No .imePadding() modifier
) {
    Column { ... }
}
```

---

## Testing Checklist

After implementing, verify:

- [ ] Open dialog → Keyboard appears automatically
- [ ] Press "Done" (✔) button → Keyboard disappears
- [ ] Tap outside TextField → Keyboard disappears
- [ ] Press "Enviar" button → Keyboard disappears, action executes
- [ ] Scroll content while keyboard visible → Can reach all buttons
- [ ] Close/reopen dialog → No keyboard stuck issues
- [ ] Fast tapping → No duplicate submissions or crashes

---

## References

**Research Sources:**
1. [Keyboard handling in Jetpack Compose - DEV Community](https://dev.to/tkuenneth/keyboard-handling-in-jetpack-compose-2593)
2. [Keyboard Handling in Jetpack Compose: Best Practices](https://canopas.com/keyboard-handling-in-jetpack-compose-all-you-need-to-know-3e6fddd30d9a)
3. [How to show/hide keyboard inside AlertDialog?](https://www.androidbugfix.com/2021/11/compose-how-to-showhide-keyboard-inside.html)
4. [Jetpack Compose Keyboard: Quick Tips](https://medium.com/@waghmaremayur855/jetpack-compose-keyboard-quick-tips-4c791cec88b2)

**Official Documentation:**
- [Handle keyboard actions | Jetpack Compose](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/commands)

---

## Implementation History

- **2025-12-17**: Implemented in `SupportScreen.kt` for bug report/feature suggestion dialogs
  - File: `app/src/main/java/com/jaac/avoqado_tpv/features/support/presentation/SupportScreen.kt`
  - Lines: 700-800 (FeedbackDialog composable)
  - Issue: Keyboard covering "Enviar" button, no way to dismiss
  - Solution: Applied complete 7-step pattern documented above
  - Result: ✅ Keyboard dismisses via Done button, tap outside, or Enviar button click

---

## Quick Reference

### Imports Needed

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
```

### Essential Modifiers

```kotlin
// Card/Container
.imePadding()

// Column/Content
.verticalScroll(rememberScrollState())
.pointerInput(Unit) {
    detectTapGestures(onTap = { focusManager.clearFocus() })
}

// TextField
.focusRequester(focusRequester)
```

### Essential Configuration

```kotlin
// KeyboardOptions
keyboardOptions = KeyboardOptions(
    imeAction = ImeAction.Done
)

// KeyboardActions
keyboardActions = KeyboardActions(
    onDone = { focusManager.clearFocus() }
)
```

---

**For questions or issues**, refer to this document or the implementation in `SupportScreen.kt`.
