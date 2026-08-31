package de.lino.cloud.platform.desktop;

import de.lino.cloud.platform.rest.api.dto.Dtos;
import de.lino.cloud.platform.rest.api.ApiClient;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.session.TokenStore;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;
import de.lino.cloud.platform.rest.api.session.TokenStoreFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Entry point. Wires {@link ApiClient} + {@link TokenStore} into a {@link SessionManager}, tries
 * to restore a previous session on startup, and shows either the login screen or the file-list
 * screen accordingly - every screen switch after that goes through {@link #showLoginScreen()}/
 * {@link #showRegisterScreen()}/{@link #showFileListScreen()}, so {@link
 * LoginController#onAuthenticated}/{@link RegisterController#onAuthenticated} and {@link
 * FileListController#onSessionExpired} all just call back into this class.
 *
 * <p>Every screen is built as a plain JavaFX scene graph styled by {@code desktop.css} (see {@link
 * #STYLESHEET}) rather than inline {@code -fx-*} calls scattered through this class - one shared
 * stylesheet, applied to every {@link Scene} this class creates via {@link #applyStylesheet}, so
 * the login/register/file-list screens read as one consistent, deliberately designed desktop instead
 * of three unrelated ad-hoc layouts.
 *
 * <p><b>Configuration:</b> the two base URLs below point at the {@code auth.cloud-driver.de}/
 * {@code api.cloud-driver.de} reverse-proxy vhosts fronting the single real {@code cloud-driver}
 * REST server (see {@link ApiClient}'s own Javadoc for why there are still two constructor
 * arguments even though there's only one backend today). In a real build, read these from a
 * config file or environment variables instead of hardcoding them.
 */
public final class MainApp extends Application {

    private static final String AUTH_PANEL_BASE_URL = "https://auth.cloud-driver.de";
    private static final String API_BASE_URL = "https://api.cloud-driver.de";

    /** Classpath location of the shared stylesheet every screen in this class applies. */
    private static final String STYLESHEET = "/de/lino/cloud/platform/desktop/app.css";

    private SessionManager sessionManager;
    private ApiClient apiClient;
    private Stage stage;

    @Override
    public void start(final Stage primaryStage) {
        this.stage = primaryStage;
        this.stage.setTitle("Cloud Driver");
        this.stage.setMinWidth(420);
        this.stage.setMinHeight(480);

        this.apiClient = new ApiClient(AUTH_PANEL_BASE_URL, API_BASE_URL);
        final TokenStoreFactory.Result tokenStoreResult = TokenStoreFactory.create();
        this.sessionManager = new SessionManager(this.apiClient, tokenStoreResult.store());

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
        emailField.setPromptText("you@example.com");
        emailField.getStyleClass().add("input-field");

        final PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.getStyleClass().add("input-field");

        final Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        final Button loginButton = new Button("Log In");
        loginButton.getStyleClass().add("button-primary");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setDefaultButton(true);

        final LoginController controller = new LoginController(
                this.sessionManager, emailField, passwordField, statusLabel, this::showFileListScreen
        );
        loginButton.setOnAction(event -> controller.onLoginClicked());

        final Label switchLabel = new Label("Don't have an account?");
        switchLabel.getStyleClass().add("status-label");
        final Button registerLinkButton = new Button("Create one");
        registerLinkButton.getStyleClass().add("button-link");
        registerLinkButton.setOnAction(event -> this.showRegisterScreen());
        final HBox switchRow = new HBox(4, switchLabel, registerLinkButton);
        switchRow.setAlignment(Pos.CENTER);

        final VBox card = authCard(
                "Welcome back", "Sign in to your Cloud Driver account",
                labeledField("Email", emailField),
                labeledField("Password", passwordField),
                loginButton,
                statusLabel,
                switchRow
        );

        this.showAuthScreen(card);
    }

    private void showRegisterScreen() {
        final TextField emailField = new TextField();
        emailField.setPromptText("you@example.com");
        emailField.getStyleClass().add("input-field");

        final PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.getStyleClass().add("input-field");

        final PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirm password");
        confirmPasswordField.getStyleClass().add("input-field");

        final Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        final Button registerButton = new Button("Create Account");
        registerButton.getStyleClass().add("button-primary");
        registerButton.setMaxWidth(Double.MAX_VALUE);
        registerButton.setDefaultButton(true);

        final VBox detailsStep = new VBox(18,
                labeledField("Email", emailField),
                labeledField("Password", passwordField),
                labeledField("Confirm password", confirmPasswordField),
                registerButton
        );

        // Step two - shown only after step one e-mails a verification code (see
        // RegisterController's own Javadoc for why registration can't complete in one call).
        final Label codeHint = new Label("Enter the code we emailed you. It expires in 10 minutes.");
        codeHint.getStyleClass().add("status-label");
        codeHint.setWrapText(true);

        final TextField codeField = new TextField();
        codeField.setPromptText("6-digit code");
        codeField.getStyleClass().add("input-field");

        final Button confirmButton = new Button("Verify & Create Account");
        confirmButton.getStyleClass().add("button-primary");
        confirmButton.setMaxWidth(Double.MAX_VALUE);

        final VBox verificationStep = new VBox(18, codeHint, labeledField("Verification code", codeField), confirmButton);
        verificationStep.setVisible(false);
        verificationStep.setManaged(false);

        final RegisterController controller = new RegisterController(
                this.sessionManager, emailField, passwordField, confirmPasswordField, codeField, statusLabel,
                () -> {
                    detailsStep.setVisible(false);
                    detailsStep.setManaged(false);
                    verificationStep.setVisible(true);
                    verificationStep.setManaged(true);
                    registerButton.setDefaultButton(false);
                    confirmButton.setDefaultButton(true);
                },
                this::showFileListScreen
        );
        registerButton.setOnAction(event -> controller.onRegisterClicked());
        confirmButton.setOnAction(event -> controller.onConfirmClicked());

        final Label switchLabel = new Label("Already have an account?");
        switchLabel.getStyleClass().add("status-label");
        final Button loginLinkButton = new Button("Log in");
        loginLinkButton.getStyleClass().add("button-link");
        loginLinkButton.setOnAction(event -> this.showLoginScreen());
        final HBox switchRow = new HBox(4, switchLabel, loginLinkButton);
        switchRow.setAlignment(Pos.CENTER);

        final VBox card = authCard(
                "Create your account", "Get started with Cloud Driver",
                detailsStep,
                verificationStep,
                statusLabel,
                switchRow
        );

        this.showAuthScreen(card);
    }

    /** Wraps {@code card} in a centered {@link StackPane} and shows it on {@link #stage}. */
    private void showAuthScreen(final VBox card) {
        final StackPane root = new StackPane(card);
        root.setPadding(new Insets(24));

        final Scene scene = new Scene(root, 440, 560);
        applyStylesheet(scene);
        this.stage.setScene(scene);
    }

    /** Builds the shared white "card" panel both the login and register screens sit inside. */
    private static VBox authCard(final String title, final String subtitle, final javafx.scene.Node... content) {
        final Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("desktop-title");

        final Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("desktop-subtitle");

        final VBox card = new VBox(18, titleLabel, subtitleLabel);
        card.getChildren().addAll(content);
        card.getStyleClass().add("auth-card");
        card.setMaxWidth(360);
        card.setAlignment(Pos.TOP_LEFT);
        return card;
    }

    /** A small caption {@link Label} stacked above {@code field}, matching {@code desktop.css}'s {@code .field-label}. */
    private static VBox labeledField(final String labelText, final javafx.scene.control.Control field) {
        final Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        final VBox group = new VBox(6, label, field);
        return group;
    }

    private void showFileListScreen() {
        final ListView<Dtos.StoredFileResponse> fileListView = new ListView<>();
        fileListView.getStyleClass().add("file-list");
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        final Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        final Button uploadButton = new Button("Upload...");
        uploadButton.getStyleClass().add("button-secondary");
        final Button deleteButton = new Button("Delete selected");
        deleteButton.getStyleClass().add("button-danger");
        final Button logoutButton = new Button("Log out");
        logoutButton.getStyleClass().add("button-secondary");

        final FileListController controller = new FileListController(
                this.sessionManager, fileListView, statusLabel, this.stage, this::showLoginScreen
        );
        uploadButton.setOnAction(event -> controller.onUploadClicked());
        deleteButton.setOnAction(event -> controller.onDeleteClicked());
        logoutButton.setOnAction(event -> this.onLogoutClicked());

        final Label title = new Label("Your Files");
        title.getStyleClass().add("toolbar-title");

        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        final HBox toolbar = new HBox(10, title, spacer, uploadButton, deleteButton, logoutButton);
        toolbar.getStyleClass().add("desktop-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        final VBox content = new VBox(12, fileListView, statusLabel);
        content.getStyleClass().add("desktop-content");
        VBox.setVgrow(content, Priority.ALWAYS);

        final BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(content);

        final Scene scene = new Scene(root, 640, 520);
        applyStylesheet(scene);
        this.stage.setScene(scene);
        controller.refresh();
    }

    private static void applyStylesheet(final Scene scene) {
        scene.getStylesheets().add(MainApp.class.getResource(STYLESHEET).toExternalForm());
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

    /** JavaFX calls this after the last window closes - shuts down {@link ApiClient}'s executor so the JVM can exit promptly. */
    @Override
    public void stop() {
        if (this.apiClient != null) {
            this.apiClient.close();
        }
    }

    public static void main(final String[] args) {
        launch(args);
    }

}
