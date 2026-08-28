package de.lino.cloud.platform.app;

import de.lino.cloud.platform.rest.api.ApiClient.ApiException;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Drives the register screen against {@link SessionManager#register} - the server's opt-in
 * self-registration route ({@code POST /auth/register}), which creates the account and logs it
 * in as one call, so a successful call here leaves the caller already authenticated and the
 * resulting token already persisted, the same as a successful {@link LoginController} run.
 *
 * <p>Checks that the password and confirmation field match locally before ever calling the
 * server - a pure client-side convenience (typo protection), not a security boundary; the
 * server does its own validation (email syntax + a live MX-record check) regardless.
 */
public final class RegisterController {

    private final SessionManager sessionManager;
    private final TextField emailField;
    private final PasswordField passwordField;
    private final PasswordField confirmPasswordField;
    private final Label statusLabel;

    /** Invoked on the JavaFX Application Thread once registration has actually succeeded. */
    private final Runnable onAuthenticated;

    public RegisterController(final SessionManager sessionManager, final TextField emailField,
                               final PasswordField passwordField, final PasswordField confirmPasswordField,
                               final Label statusLabel, final Runnable onAuthenticated) {
        this.sessionManager = sessionManager;
        this.emailField = emailField;
        this.passwordField = passwordField;
        this.confirmPasswordField = confirmPasswordField;
        this.statusLabel = statusLabel;
        this.onAuthenticated = onAuthenticated;
    }

    /** Wire this to your "Create account" button's {@code onAction}. */
    public void onRegisterClicked() {
        final String email = this.emailField.getText();
        final String password = this.passwordField.getText();

        if (password.isEmpty()) {
            this.showError("Enter a password.");
            return;
        }
        if (!password.equals(this.confirmPasswordField.getText())) {
            this.showError("Passwords do not match.");
            return;
        }

        this.statusLabel.getStyleClass().removeAll("status-label-error");
        this.statusLabel.setText("Creating your account...");

        final Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws ApiException, TokenStoreException {
                RegisterController.this.sessionManager.register(email, password);
                return null;
            }
        };

        task.setOnSucceeded(event -> Platform.runLater(() -> {
            this.statusLabel.getStyleClass().removeAll("status-label-error");
            this.statusLabel.setText("Account created.");
            this.onAuthenticated.run();
        }));

        task.setOnFailed(event -> Platform.runLater(() -> {
            final Throwable failure = task.getException();
            if (failure instanceof TokenStoreException) {
                // Registration itself already succeeded server-side (and ApiClient already
                // holds the token in memory) - only local persistence failed, so this session
                // still works until the app is closed. Proceed rather than stranding the user.
                this.statusLabel.getStyleClass().removeAll("status-label-error");
                this.statusLabel.setText("Account created, but could not save your session locally: " + failure.getMessage());
                this.onAuthenticated.run();
                return;
            }
            this.showError(describe(failure));
        }));

        Thread.ofVirtual().name("api-call").start(task);
    }

    private void showError(final String message) {
        this.statusLabel.setText(message);
        if (!this.statusLabel.getStyleClass().contains("status-label-error")) {
            this.statusLabel.getStyleClass().add("status-label-error");
        }
    }

    private static String describe(final Throwable failure) {
        if (failure instanceof ApiException apiException) {
            return switch (apiException.statusCode()) {
                case 409 -> "An account with this email already exists.";
                case 400 -> apiException.getMessage();
                case 0 -> "Could not reach the server: " + apiException.getMessage();
                default -> "Something went wrong: " + apiException.getMessage();
            };
        }
        return "Unexpected error: " + failure.getMessage();
    }

}
