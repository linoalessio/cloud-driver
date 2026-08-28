package de.lino.clouddriver.desktop;

import de.lino.clouddriver.desktop.api.ApiClient;
import de.lino.clouddriver.desktop.api.SessionManager;
import de.lino.clouddriver.desktop.api.session.TokenStore;
import de.lino.clouddriver.desktop.api.session.TokenStoreException;
import de.lino.clouddriver.desktop.api.session.TokenStoreFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Entry point. Wires {@link ApiClient} + {@link TokenStore} into a {@link SessionManager}, tries
 * to restore a previous session on startup, and shows either the login screen or the file-list
 * screen accordingly - every screen switch after that goes through {@link #showLoginScreen()}/
 * {@link #showFileListScreen()}, so {@link LoginController#onAuthenticated} and {@link
 * FileListController#onSessionExpired} both just call back into this class.
 *
 * <p><b>Configuration:</b> the two base URLs below are placeholders - point them at your actual
 * {@code cloud-driver} deployment (auth-panel port and main REST port respectively; see {@link
 * ApiClient}'s own Javadoc for why there are two). In a real build, read these from a config
 * file or environment variables instead of hardcoding them.
 */
public final class MainApp extends Application {

    private static final String AUTH_PANEL_BASE_URL = "https://auth.cloud-driver.de";
    private static final String API_BASE_URL = "https://api.cloud-driver.de";

    private SessionManager sessionManager;
    private Stage stage;

    @Override
    public void start(final Stage primaryStage) {
        this.stage = primaryStage;
        this.stage.setTitle("Cloud Driver");

        final ApiClient apiClient = new ApiClient(AUTH_PANEL_BASE_URL, API_BASE_URL);
        final TokenStoreFactory.Result tokenStoreResult = TokenStoreFactory.create();
        this.sessionManager = new SessionManager(apiClient, tokenStoreResult.store());

        if (tokenStoreResult.usedFallback()) {
            this.warnAboutFallbackStorage();
        }

        this.stage.show();
        this.tryRestoreSessionThenShowScreen();
    }

    /**
     * Runs {@link SessionManager#tryRestoreSession()} off the FX thread (it makes a real HTTP
     * call), then shows whichever screen matches the result.
     */
    private void tryRestoreSessionThenShowScreen() {
        Thread.ofVirtual().name("session-restore").start(() -> {
            boolean restored;
            try {
                restored = this.sessionManager.tryRestoreSession();
            } catch (final TokenStoreException e) {
                // Could not even read the local token store - treat as "no session", same as a
                // fresh install. The user just has to log in once more.
                restored = false;
            }
            final boolean sessionRestored = restored;
            Platform.runLater(() -> {
                if (sessionRestored) {
                    this.showFileListScreen();
                } else {
                    this.showLoginScreen();
                }
            });
        });
    }

    private void showLoginScreen() {
        final TextField emailField = new TextField();
        emailField.setPromptText("Email");

        final PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        final Label statusLabel = new Label();

        final Button loginButton = new Button("Login");
        final Button registerButton = new Button("Register");

        final LoginController controller = new LoginController(
                this.sessionManager, emailField, passwordField, statusLabel, this::showFileListScreen
        );
        loginButton.setOnAction(event -> controller.onLoginClicked());
        registerButton.setOnAction(event -> controller.onRegisterClicked());

        final VBox root = new VBox(10, emailField, passwordField, loginButton, registerButton, statusLabel);
        root.setPadding(new Insets(20));

        this.stage.setScene(new Scene(root, 320, 220));
    }

    private void showFileListScreen() {
        final ListView<de.lino.clouddriver.desktop.api.dto.Dtos.StoredFileResponse> fileListView = new ListView<>();
        final Label statusLabel = new Label();

        final Button uploadButton = new Button("Upload...");
        final Button deleteButton = new Button("Delete selected");
        final Button logoutButton = new Button("Log out");

        final FileListController controller = new FileListController(
                this.sessionManager, fileListView, statusLabel, this.stage, this::showLoginScreen
        );
        uploadButton.setOnAction(event -> controller.onUploadClicked());
        deleteButton.setOnAction(event -> controller.onDeleteClicked());
        logoutButton.setOnAction(event -> this.onLogoutClicked());

        final VBox root = new VBox(10, fileListView, uploadButton, deleteButton, logoutButton, statusLabel);
        root.setPadding(new Insets(20));

        this.stage.setScene(new Scene(root, 480, 400));
        controller.refresh();
    }

    private void onLogoutClicked() {
        try {
            this.sessionManager.logout();
        } catch (final TokenStoreException e) {
            // The in-memory session is already cleared by SessionManager#logout before this
            // could throw - only the persisted copy might survive. Not worth blocking the UI
            // over; the next restore attempt will just hit an invalid/expired token anyway.
            System.err.println("@MainApp: failed to clear persisted session on logout: " + e.getMessage());
        }
        this.showLoginScreen();
    }

    private void warnAboutFallbackStorage() {
        final Alert alert = new Alert(AlertType.WARNING);
        alert.setTitle("No system keychain found");
        alert.setHeaderText(null);
        alert.setContentText(
                "Your session will be stored in a local file instead of your system's secure "
                        + "keychain. This still requires access to your user account, but is less "
                        + "protected than the OS keychain."
        );
        alert.showAndWait();
    }

    public static void main(final String[] args) {
        launch(args);
    }

}
