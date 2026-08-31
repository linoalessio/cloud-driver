package de.lino.cloud.platform.desktop;

import de.lino.cloud.platform.rest.api.session.TokenStoreFactory;
import de.lino.cloud.platform.rest.api.ApiClient.ApiException;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Drives the login screen against {@link SessionManager} - not the raw {@code ApiClient} - so a
 * successful login is automatically persisted to the OS keychain (see {@link TokenStoreFactory})
 * and available again on the next desktop start via {@link SessionManager#tryRestoreSession()}.
 *
 * <p>No "register" flow of its own - see {@link RegisterController} for that screen; both drive
 * {@link SessionManager} the same way and share the same {@link #onAuthenticated} shape.
 */
public final class LoginController {

    private final SessionManager sessionManager;
    private final TextField emailField;
    private final PasswordField passwordField;
    private final Label statusLabel;

    /** Invoked on the JavaFX Application Thread once login has actually succeeded. */
    private final Runnable onAuthenticated;

    public LoginController(final SessionManager sessionManager, final TextField emailField,
                            final PasswordField passwordField, final Label statusLabel,
                            final Runnable onAuthenticated) {
        this.sessionManager = sessionManager;
        this.emailField = emailField;
        this.passwordField = passwordField;
        this.statusLabel = statusLabel;
        this.onAuthenticated = onAuthenticated;
    }

    /** Wire this to your "Login" button's {@code onAction}. */
    public void onLoginClicked() {
        this.runAuthTask(sm -> sm.login(this.emailField.getText(), this.passwordField.getText()));
    }

    private void runAuthTask(final AuthCall call) {
        this.statusLabel.getStyleClass().removeAll("status-label-error");
        this.statusLabel.setText("Working...");

        final Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws ApiException, TokenStoreException {
                call.run(LoginController.this.sessionManager);
                return null;
            }
        };

        task.setOnSucceeded(event -> Platform.runLater(() -> {
            this.statusLabel.getStyleClass().removeAll("status-label-error");
            this.statusLabel.setText("Logged in.");
            this.onAuthenticated.run();
        }));

        task.setOnFailed(event -> Platform.runLater(() -> {
            final Throwable failure = task.getException();
            this.statusLabel.setText(describe(failure));
            if (failure instanceof TokenStoreException) {
                // Login itself already succeeded server-side (and ApiClient already
                // holds the token in memory) - only local persistence failed, so this session
                // still works until the desktop is closed. Proceed rather than stranding the user
                // on the login screen after a technically-successful login.
                this.statusLabel.getStyleClass().removeAll("status-label-error");
                this.onAuthenticated.run();
                return;
            }
            if (!this.statusLabel.getStyleClass().contains("status-label-error")) {
                this.statusLabel.getStyleClass().add("status-label-error");
            }
        }));

        Thread.ofVirtual().name("api-call").start(task);
    }

    private static String describe(final Throwable failure) {
        if (failure instanceof ApiException apiException) {
            return switch (apiException.statusCode()) {
                case 401 -> "Wrong email or password.";
                case 400 -> apiException.getMessage();
                default -> "Something went wrong: " + apiException.getMessage();
            };
        }
        if (failure instanceof TokenStoreException) {
            // Login itself succeeded server-side - only persisting the token locally
            // failed. Worth telling the user, but they are logged in for this session either way.
            return "Logged in, but could not save your session locally: " + failure.getMessage();
        }
        return "Unexpected error: " + failure.getMessage();
    }

    @FunctionalInterface
    private interface AuthCall {
        void run(SessionManager sessionManager) throws ApiException, TokenStoreException;
    }

}
