package de.lino.cloud.platform.desktop.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.theme.CardShape
import de.lino.cloud.platform.desktop.utils.PASSWORD_REQUIREMENT_HINT
import de.lino.cloud.platform.desktop.utils.isValidPasswordFormat
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platforms.desktop.cloud_driver_platforms_desktop.generated.resources.Res
import de.lino.cloud.platforms.desktop.cloud_driver_platforms_desktop.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

private val FIELD_SHAPE = RoundedCornerShape(12.dp)

@Composable
private fun AuthCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(Res.drawable.app_icon),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Text(
                "cloud-driver",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            Card(
                modifier = Modifier.widthIn(max = 420.dp),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    content()
                }
            }
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = FIELD_SHAPE,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        supportingText = supportingText?.let { text -> { Text(text) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(text: String, busyText: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) busyText else text) }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun LoginScreen(viewModel: AppViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AuthCard("Sign in") {
        AuthTextField(email, { email = it }, "Email")
        AuthTextField(password, { password = it }, "Password", isPassword = true)

        ErrorText(viewModel.errorMessage)

        PrimaryButton(
            text = "Sign in", busyText = "Signing in...", busy = viewModel.busy,
            enabled = !viewModel.busy && email.isNotBlank() && password.isNotBlank(),
            onClick = { viewModel.login(email, password) },
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.startRegister() }) { Text("Create account") }
            TextButton(onClick = { viewModel.startResetPassword() }) { Text("Forgot password?") }
        }
    }
}

@Composable
fun RegisterScreen(viewModel: AppViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val mismatch = confirmPassword.isNotEmpty() && password != confirmPassword
    val passwordFormatValid = isValidPasswordFormat(password)
    val showPasswordFormatError = password.isNotEmpty() && !passwordFormatValid

    AuthCard("Create account") {
        AuthTextField(email, { email = it }, "Email")
        AuthTextField(
            password, { password = it }, "Password", isPassword = true,
            isError = showPasswordFormatError,
            supportingText = PASSWORD_REQUIREMENT_HINT,
        )
        AuthTextField(confirmPassword, { confirmPassword = it }, "Confirm password", isPassword = true, isError = mismatch)

        if (mismatch) Text("Passwords do not match", color = MaterialTheme.colorScheme.error)
        ErrorText(viewModel.errorMessage)

        PrimaryButton(
            text = "Send verification code", busyText = "Sending code...", busy = viewModel.busy,
            enabled = !viewModel.busy && email.isNotBlank() && passwordFormatValid && !mismatch,
            onClick = { viewModel.register(email, password) },
        )

        TextButton(onClick = { viewModel.backToLogin() }) { Text("Back to sign in") }
    }
}

@Composable
fun RegisterConfirmScreen(viewModel: AppViewModel, email: String) {
    var code by remember { mutableStateOf("") }

    AuthCard("Verify $email") {
        Text("Enter the 6-digit code we emailed you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AuthTextField(code, { code = it }, "Verification code")

        ErrorText(viewModel.errorMessage)

        PrimaryButton(
            text = "Verify and sign in", busyText = "Verifying...", busy = viewModel.busy,
            enabled = !viewModel.busy && code.isNotBlank(),
            onClick = { viewModel.confirmRegister(email, code) },
        )

        TextButton(onClick = { viewModel.backToLogin() }) { Text("Back to sign in") }
    }
}

@Composable
fun ResetPasswordRequestScreen(viewModel: AppViewModel) {
    var email by remember { mutableStateOf("") }

    AuthCard("Reset password") {
        Text("Enter your account email - we'll send you a verification code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AuthTextField(email, { email = it }, "Email")

        ErrorText(viewModel.errorMessage)

        PrimaryButton(
            text = "Send reset code", busyText = "Sending code...", busy = viewModel.busy,
            enabled = !viewModel.busy && email.isNotBlank(),
            onClick = { viewModel.requestPasswordReset(email) },
        )

        TextButton(onClick = { viewModel.backToLogin() }) { Text("Back to sign in") }
    }
}

@Composable
fun ResetPasswordConfirmScreen(viewModel: AppViewModel, email: String) {
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    val passwordFormatValid = isValidPasswordFormat(newPassword)
    val showPasswordFormatError = newPassword.isNotEmpty() && !passwordFormatValid

    AuthCard("Reset password for $email") {
        AuthTextField(code, { code = it }, "Verification code")
        AuthTextField(
            newPassword, { newPassword = it }, "New password", isPassword = true,
            isError = showPasswordFormatError,
            supportingText = PASSWORD_REQUIREMENT_HINT,
        )
        AuthTextField(confirmPassword, { confirmPassword = it }, "Confirm new password", isPassword = true, isError = mismatch)

        if (mismatch) Text("Passwords do not match", color = MaterialTheme.colorScheme.error)
        ErrorText(viewModel.errorMessage)

        PrimaryButton(
            text = "Reset password and sign in", busyText = "Resetting...", busy = viewModel.busy,
            enabled = !viewModel.busy && code.isNotBlank() && passwordFormatValid && !mismatch,
            onClick = { viewModel.confirmPasswordReset(email, code, newPassword) },
        )

        TextButton(onClick = { viewModel.backToLogin() }) { Text("Back to sign in") }
    }
}
