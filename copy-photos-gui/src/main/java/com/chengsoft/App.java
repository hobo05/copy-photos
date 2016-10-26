package com.chengsoft;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.chengsoft.PhotoProcessor.*;

/**
 * Created by Tim on 2/14/2016.
 */
public class App extends Application implements Initializable {
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    public static final String ERROR_LOG_FILENAME_PROP = "errorLogFilename";
    public static final String ERROR_LOG_FILENAME = "error.log";

    @FXML
    private TextField textFieldSourceFolder;
    @FXML
    private TextField textFieldDestFolder;
    @FXML
    private TextField textFieldIgnoreFolders;
    @FXML
    private Button buttonProcess;
    @FXML
    private Button buttonCancel;

    private DirectoryChooser directoryChooser = new DirectoryChooser();

    private static Stage stage;

    private Desktop desktop = Desktop.getDesktop();

    @FXML
    private TextArea textAreaLoggingView;

    // Predicate for accepting a folder
    private static final Predicate<Dragboard> SINGLE_FOLDER_PREDICATE = db ->
            db.hasFiles()
                    && db.getFiles().size() == 1
                    && db.getFiles().get(0).isDirectory();

    private Optional<Path> errorLogPath = Optional.empty();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        stage.setTitle("Copy Photos");
        Parent root = FXMLLoader.load(getClass().getClassLoader().getResource("gui.fxml"));
        stage.setScene(new Scene(root));
        stage.show();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        TextAreaAppender.setTextArea(textAreaLoggingView);

        buttonCancel.setOnAction(e -> Platform.exit());

        // Open directory chooser when textFieldSourceFolder or textFieldDestFolder are clicked
        textFieldSourceFolder.setOnMouseClicked(e -> this.chooseFolder(textFieldSourceFolder, "source"));
        textFieldDestFolder.setOnMouseClicked(e -> this.chooseFolder(textFieldDestFolder, "destination"));

        // Drag and drop support
        textFieldSourceFolder.setOnDragOver(this::onDragOver);
        textFieldDestFolder.setOnDragOver(this::onDragOver);
        textFieldSourceFolder.setOnDragDropped(e -> this.onDragDropped(textFieldSourceFolder, e));
        textFieldDestFolder.setOnDragDropped(e -> this.onDragDropped(textFieldDestFolder, e));

        buttonProcess.setOnAction(e -> {
            if (Strings.isNullOrEmpty(textFieldSourceFolder.getText())) {
                showFatalError("There must be an source image folder to process");
                return;
            }

            if (Strings.isNullOrEmpty(textFieldDestFolder.getText())) {
                showFatalError("There must be an destination image folder to process");
                return;
            }

            // Set the output folder of the error log
            setErrorLog(textFieldDestFolder.getText());

            Path destFolderPath = Paths.get(textFieldDestFolder.getText());
            if (!Files.isDirectory(destFolderPath)) {
                showFatalError("The destination image folder must be a valid directory");
                return;
            }

            List<String> ignoreFolderList = ImmutableList.of();
            if (!Strings.isNullOrEmpty(textFieldIgnoreFolders.getText())) {
                ignoreFolderList = Stream.of(textFieldIgnoreFolders.getText().split(","))
                        .map(String::trim)
                        .collect(Collectors.toList());
            }

            stage.getScene().setCursor(Cursor.WAIT);

            Integer dryRunCount = copyFiles(textFieldSourceFolder.getText(),
                    textFieldDestFolder.getText(), ignoreFolderList, Media.ALL, true)
                    .count()
                    .toBlocking()
                    .single();

            copyFiles(textFieldSourceFolder.getText(),
                    textFieldDestFolder.getText(), ignoreFolderList, Media.ALL, false)
                    .count()
                    .doAfterTerminate(() -> {
                        stage.getScene().setCursor(Cursor.DEFAULT);
                        errorLogPath.ifPresent(p -> {
                            try {
                                if (Files.size(p) > 0) {
                                    showWarning("Please check error log for details");
                                    openFolder(p);
                                }
                            } catch (IOException e1) {
                                logger.error("Could not get filesize: ", e1);
                            }
                        });
                    })
                    .subscribe(
                            c -> logger.info("Files attempted to copy: {} Actual copied: {}", dryRunCount, c),
                            ex -> showFatalError("Failed to copy photos", ex),
                            () -> {
                                showSuccess();
                                openFolder(destFolderPath);
                            });

        });
    }

    private void setErrorLog(String logFolder) {
        // Delete existing error log if it exists
        errorLogPath = Optional.of(Paths.get(logFolder).resolve(ERROR_LOG_FILENAME));
        try {
            new PrintWriter(errorLogPath.get().toFile()).close();
        } catch (IOException e) {
            logger.error("Failed to clear content of errorLog={}", errorLogPath.get());
        }

        System.setProperty(ERROR_LOG_FILENAME_PROP, errorLogPath.get().toString());
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.reconfigure();
    }

    private void openFolder(Path folderPath) {
        try {
            desktop.open(folderPath.toFile());
        } catch (IOException e1) {
            showFatalError("Failed to open folder=" + folderPath, e1);
        }
    }

    private void showSuccess() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setHeaderText("Processing has completed!");
        alert.showAndWait();
    }

    private void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("There were errors!");
        alert.setHeaderText(message);
        alert.showAndWait();
    }

    private void showFatalError(String message) {
        showFatalError(message, null, null);
    }

    private void showFatalError(String message, Throwable throwable) {
        showFatalError(message, throwable.getMessage(), throwable);
    }

    private void showFatalError(String message, String body, Throwable throwable) {
        // Log throwable
        if (Objects.nonNull(throwable))
            logger.error(body, throwable);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Fatal Error!");
        alert.setHeaderText(message);
        alert.setContentText(body);
        alert.showAndWait();
    }

    private void chooseFolder(TextField textField, String type) {
        directoryChooser.setTitle(String.format("Choose %s image folder", type));
        directoryChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        Optional.ofNullable(directoryChooser.showDialog(this.stage))
                .ifPresent(f -> {
                    logger.info("{} folder set as {}", type, f.getAbsolutePath());
                    textField.setText(f.getAbsolutePath());
                });
    }

    private void onDragOver(DragEvent e) {
        Dragboard db = e.getDragboard();
        if (SINGLE_FOLDER_PREDICATE.test(db)) {
            e.acceptTransferModes(TransferMode.ANY);
        } else {
            e.consume();
        }
    }

    private void onDragDropped(TextField textField, DragEvent e) {
        Dragboard db = e.getDragboard();
        boolean success = false;
        if (SINGLE_FOLDER_PREDICATE.test(db)) {
            success = true;
            File droppedFolder = db.getFiles().get(0);
            textField.setText(droppedFolder.getAbsolutePath());
        }
        e.setDropCompleted(success);
        e.consume();
    }

}