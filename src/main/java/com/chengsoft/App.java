package com.chengsoft;

import com.google.common.base.Strings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.*;
import javafx.scene.Cursor;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.*;
import java.io.File;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Predicate;

/**
 * Created by Tim on 2/14/2016.
 */
public class App extends Application implements Initializable {
    @FXML
    private TextField textFieldInputExcel;
    @FXML
    private TextField textFieldOutputFolder;
    @FXML
    private Button buttonProcess;
    @FXML
    private Button buttonCancel;

    private FileChooser fileChooser = new FileChooser();
    private static Stage stage;

    private static PathMatcher EXCEL_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.{xls,xlsx}");

    private Desktop desktop = Desktop.getDesktop();

    public static void main(String[] args) {launch(args);}

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
        buttonCancel.setOnAction(e -> Platform.exit());

        // Open file chooser when textFieldInputExcel is clicked
        textFieldInputExcel.setOnMouseClicked(event -> {
            configureFileChooser(fileChooser);
            Optional<File> file = Optional.ofNullable(fileChooser.showOpenDialog(this.stage));
            file.ifPresent(f -> {
                textFieldInputExcel.setText(f.getAbsolutePath());
                textFieldOutputFolder.setText(f.getParentFile().getAbsolutePath());
            });
        });

        // Predicate for accepting excel file
        Predicate<Dragboard> singleExcelFilePredicate = db ->
                db.hasFiles()
                && db.getFiles().size() == 1
                && EXCEL_MATCHER.matches(db.getFiles().get(0).toPath());

        // Drag and drop support
        textFieldInputExcel.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (singleExcelFilePredicate.test(db)) {
                e.acceptTransferModes(TransferMode.ANY);
            } else {
                e.consume();
            }
        });
        textFieldInputExcel.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (singleExcelFilePredicate.test(db)) {
                success = true;
                File droppedFile = db.getFiles().get(0);
                textFieldInputExcel.setText(droppedFile.getAbsolutePath());
                textFieldOutputFolder.setText(droppedFile.getParentFile().getAbsolutePath());
            }
            e.setDropCompleted(success);
            e.consume();
        });

        buttonProcess.setOnAction(e -> {
            if (Strings.isNullOrEmpty(textFieldInputExcel.getText())) {
                showFatalError("There must be an excel sheet to process");
                return;
            }

            if (Strings.isNullOrEmpty(textFieldOutputFolder.getText())) {
                showFatalError("There must be an output folder to process");
                return;
            }

            Path outputFolderPath = Paths.get(textFieldOutputFolder.getText());
            if (!Files.isDirectory(outputFolderPath)) {
                showFatalError("The output folder must be a valid directory");
                return;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
            String formattedDate = formatter.format(LocalDateTime.now());
            String outputExcelFilename = String.format("Processed_Spectra_%s.xlsx", formattedDate);
            Path outputExcelPath = outputFolderPath.resolve(outputExcelFilename);
            try {
                stage.getScene().setCursor(Cursor.WAIT);

                PhotoProcessor.processAndWriteExcel(textFieldInputExcel.getText(), outputExcelPath.toAbsolutePath().toString());

                stage.getScene().setCursor(Cursor.DEFAULT);

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Success");
                alert.setHeaderText("Processing has completed!");
                alert.showAndWait();

                desktop.open(outputExcelPath.toFile());
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

    private static void configureFileChooser(final FileChooser fileChooser) {
        fileChooser.setTitle("Choose file");
        fileChooser.getExtensionFilters()
            .add(new FileChooser.ExtensionFilter("Excel Files (*.xls, *.xlsx)", "*.xls", "*.xlsx"));
        fileChooser.setInitialDirectory(
                new File(System.getProperty("user.home"))
        );
    }
}