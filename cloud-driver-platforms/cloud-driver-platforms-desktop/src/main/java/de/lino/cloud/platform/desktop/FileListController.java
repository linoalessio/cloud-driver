package de.lino.cloud.platform.desktop;

import de.lino.cloud.platform.rest.api.ApiClient.ApiException;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileResponse;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * Drives the main "your files" screen: lists, uploads, and deletes {@code StoredFile}s purely
 * over REST via {@link SessionManager#api()}. Every failure path checks {@link
 * ApiException#isUnauthorized()} and, on {@code true}, calls {@link SessionManager#handleFailure}
 * (clearing the stale session) and then {@link #onSessionExpired} - the caller wires that to
 * navigating back to the login screen, so an expired 12h token during normal use doesn't just
 * show a cryptic error, it sends the user to log in again.
 */
public final class FileListController {

    private final SessionManager sessionManager;
    private final ListView<StoredFileResponse> fileListView;
    private final Label statusLabel;
    private final Window ownerWindow;

    /** Invoked on the JavaFX Application Thread whenever a call comes back {@code 401}. */
    private final Runnable onSessionExpired;

    private final ObservableList<StoredFileResponse> files = FXCollections.observableArrayList();

    public FileListController(final SessionManager sessionManager, final ListView<StoredFileResponse> fileListView,
                               final Label statusLabel, final Window ownerWindow, final Runnable onSessionExpired) {
        this.sessionManager = sessionManager;
        this.fileListView = fileListView;
        this.statusLabel = statusLabel;
        this.ownerWindow = ownerWindow;
        this.onSessionExpired = onSessionExpired;

        this.fileListView.setItems(this.files);
        this.fileListView.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(final StoredFileResponse file, final boolean empty) {
                super.updateItem(file, empty);
                this.setText(empty || file == null ? null : file.fileName());
            }
        });
    }

    /** Call once when this screen becomes visible (after login, or desktop restart with a restored session). */
    public void refresh() {
        this.statusLabel.setText("Loading files...");
        this.runAuthenticated(
                sessionManager -> sessionManager.api().listFiles(),
                loaded -> {
                    this.files.setAll(loaded);
                    this.statusLabel.setText(loaded.size() + " file(s).");
                }
        );
    }

    /** Wire this to an "Upload" button's {@code onAction}. */
    public void onUploadClicked() {
        final FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a file to upload");
        final java.io.File chosen = chooser.showOpenDialog(this.ownerWindow);
        if (chosen == null) {
            return;
        }

        this.statusLabel.setText("Uploading " + chosen.getName() + "...");
        this.runAuthenticated(
                // Streams straight from disk (ApiClient#uploadFile(Path)) rather than reading the
                // whole file into a byte[] first - see that method's own Javadoc.
                sessionManager -> sessionManager.api().uploadFile(chosen.toPath()),
                uploaded -> {
                    this.statusLabel.setText("Uploaded " + uploaded.fileName() + ".");
                    this.refresh();
                }
        );
    }

    /** Wire this to a "Delete" button's {@code onAction}, using the list's current selection. */
    public void onDeleteClicked() {
        final StoredFileResponse selected = this.fileListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            this.statusLabel.setText("Select a file to delete first.");
            return;
        }

        this.statusLabel.setText("Deleting " + selected.fileName() + "...");
        this.runAuthenticated(
                sessionManager -> {
                    sessionManager.api().deleteFile(selected.fileId());
                    return null;
                },
                ignored -> {
                    this.statusLabel.setText("Deleted " + selected.fileName() + ".");
                    this.refresh();
                }
        );
    }

    /**
     * Runs {@code call} off the JavaFX Application Thread, then either hands the result to
     * {@code onSuccess} (back on the FX thread) or, on an {@link ApiException}, clears the
     * session and navigates to login if it was a 401, otherwise just shows the error.
     */
    private <T> void runAuthenticated(final AuthenticatedCall<T> call, final Consumer<T> onSuccess) {
        final Task<T> task = new Task<>() {
            @Override
            protected T call() throws ApiException {
                return call.run(FileListController.this.sessionManager);
            }
        };

        task.setOnSucceeded(event -> Platform.runLater(() -> onSuccess.accept(task.getValue())));

        task.setOnFailed(event -> Platform.runLater(() -> {
            final Throwable failure = task.getException();
            if (failure instanceof ApiException apiException) {
                this.handleApiFailure(apiException);
            } else {
                this.statusLabel.setText("Unexpected error: " + failure.getMessage());
            }
        }));

        Thread.ofVirtual().name("api-call").start(task);
    }

    private void handleApiFailure(final ApiException failure) {
        try {
            this.sessionManager.handleFailure(failure);
        } catch (final TokenStoreException clearFailed) {
            // Best-effort: the in-memory session is already cleared by handleFailure before
            // this could throw, so the user is logged out either way - just log it.
            System.err.println("@FileListController: failed to clear persisted session: " + clearFailed.getMessage());
        }

        if (failure.isUnauthorized()) {
            this.statusLabel.setText("Your session expired - please log in again.");
            this.onSessionExpired.run();
            return;
        }

        this.statusLabel.setText(describe(failure));
    }

    private static String describe(final ApiException failure) {
        return switch (failure.statusCode()) {
            case 404 -> "That file no longer exists.";
            case 0 -> "Could not reach the server: " + failure.getMessage();
            default -> "Something went wrong (" + failure.statusCode() + "): " + failure.getMessage();
        };
    }

    @FunctionalInterface
    private interface AuthenticatedCall<T> {
        T run(SessionManager sessionManager) throws ApiException;
    }

}
