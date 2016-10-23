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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by Tim on 2/14/2016.
 */
public class App extends Application implements Initializable {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

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

            try {
                stage.getScene().setCursor(Cursor.WAIT);
                PhotoProcessor.copyPhotos(textFieldSourceFolder.getText(), textFieldDestFolder.getText(), ignoreFolderList, false);

                stage.getScene().setCursor(Cursor.DEFAULT);

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Success");
                alert.setHeaderText("Processing has completed!");
                alert.showAndWait();

                desktop.open(destFolderPath.toFile());
            } catch (Exception e1) {
                showFatalError("Failed to process data", e1.getMessage());
            }

        });
    }

    private void showFatalError(String message) {
        showFatalError(message, null);
    }

    private void showFatalError(String message, String body) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Fatal Error!");
        alert.setHeaderText(message);
        alert.setContentText(body);
        alert.show();
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