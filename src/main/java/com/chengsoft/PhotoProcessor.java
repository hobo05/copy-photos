package com.chengsoft;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Created by Tim on 2/18/2016.
 */
public class PhotoProcessor {

    public static final String VALUE = "Value";
    public static final String PREFIX_PHO_SCANNING = "Pho_Scanning";
    public static final String PREFIX_FL_SCANNING = "Fl_Scanning";
    private static PathMatcher XLS_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.xls");
    private static PathMatcher XLSX_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.xlsx");

    public static void processAndWriteExcel(String inputExcel, String outputExcel) throws IOException, InvalidFormatException {
        Path path = Paths.get(inputExcel);

        Workbook inputWorkbook = null;
        if (XLS_MATCHER.matches(path)) {
            inputWorkbook = new HSSFWorkbook(new POIFSFileSystem(path.toFile()));
        } else if (XLSX_MATCHER.matches(path)) {
            inputWorkbook = new XSSFWorkbook(path.toFile());
        }

        // Check that there is a workbook
        if (Objects.isNull(inputWorkbook)) {
            throw new IllegalArgumentException(inputExcel + " does not contain a workbook");
        }

        Workbook outputWorkbook = new XSSFWorkbook();
        FileOutputStream fileOut = new FileOutputStream(outputExcel);

        // Loop through the workbook
        for (int i = 0; i < inputWorkbook.getNumberOfSheets(); i++) {
            Sheet curSheet = inputWorkbook.getSheetAt(i);
            if (curSheet.getSheetName().startsWith(PREFIX_PHO_SCANNING)) {
                processAndWriteSheet(outputWorkbook, ".*Wavelength: (\\d+) nm$", curSheet, "Wavelength");
            } else if (curSheet.getSheetName().startsWith(PREFIX_FL_SCANNING)) {
                processAndWriteSheet(outputWorkbook, ".*Em: (\\d+) nm$", curSheet, "Emission");
            }
        }

        outputWorkbook.write(fileOut);
        fileOut.close();
    }

    private static void processAndWriteSheet(
            Workbook outputWorkbook,
            String headerRegex,
            Sheet sheet,
            String headerName) throws IOException, InvalidFormatException {

        Pattern headerPattern = Pattern.compile(headerRegex);

        int curWavelength = 0;
        int maxListLength = 0;
        Set<Integer> waveLengthSet = Sets.newHashSet();
        Multimap<Integer, Double> absorbanceMap = ArrayListMultimap.create();
        int curRow = 0;
        int lastColumnWithData = 0;
        boolean isRecordingValues = false;
        while (curRow <= sheet.getLastRowNum()) {
            curRow++;

            // Don't process if the row is null
            // Also reset curWavelength and isRecordingValues flag
            Row row = sheet.getRow(curRow);
            if (Objects.isNull(row)) {
                isRecordingValues = false;
                continue;
            }

            // Retrieve first cell's value and see if it's the header
            Cell firstCell = row.getCell(row.getFirstCellNum(), Row.RETURN_BLANK_AS_NULL);
            String firstCellValue = firstCell.getStringCellValue();
            Matcher matcher = headerPattern.matcher(firstCellValue);
            if (matcher.matches()) {
                // set the current wavelength

                curWavelength = Integer.valueOf(matcher.group(1));
                // Add to the total set of wavelengths
                waveLengthSet.add(curWavelength);

                // continue as the row will not contain any other data
                continue;
            }

            // Check if the first cell's value is "Value"
            if (VALUE.equalsIgnoreCase(firstCellValue)) {
                // Find the index of the last numbered column header
                lastColumnWithData = findLastColumnWithData(row, null, firstCell.getColumnIndex() + 1)
                        .orElseThrow(() -> new RuntimeException("Error while finding the last column header for the 'Sample' row"));

                // Start recording values
                isRecordingValues = true;

                // continue as the row will not contain any other data
                continue;
            }

            // Only collect the values if we have encountered the VALUE row first
            if (isRecordingValues) {

                List<Double> recordedValues = IntStream.range(1, lastColumnWithData + 1)
                        .boxed()
                        .map(col -> row.getCell(col, Row.RETURN_BLANK_AS_NULL)) // get blank cells as null
                        .filter(Objects::nonNull)   // filter out all the null cells
                        .map(PhotoProcessor::getStringValue)    // return the string cell values
                        .map(Double::valueOf)   // convert them into doubles
                        .collect(Collectors.toList());

                // Only add to the map if there are values
                if (!recordedValues.isEmpty()) {
                    absorbanceMap.putAll(curWavelength, recordedValues);

                    // Determine maxListLength
                    maxListLength = Math.max(maxListLength, absorbanceMap.get(curWavelength).size());
                }
            }
        }


        Sheet outputSheet = outputWorkbook.createSheet(sheet.getSheetName());

        Row headerRow = outputSheet.createRow(0);
        headerRow.createCell(0).setCellValue(headerName);
        IntStream.range(1, maxListLength + 1).boxed()
                .forEach(i -> headerRow.createCell(i).setCellValue(i));

        new TreeMap<>(absorbanceMap.asMap()).entrySet().stream()
                .forEach(entry -> {
                    Row newRow = outputSheet.createRow(outputSheet.getLastRowNum() + 1);
                    newRow.createCell(0).setCellValue(entry.getKey());
                    entry.getValue().stream()
                            .forEach(s -> {
                                int newCellNum = newRow.getLastCellNum();
                                Cell newCell = newRow.createCell(newCellNum);
                                newCell.setCellValue(s);
                            });
                });
    }

    private static Optional<Integer> findLastColumnWithData(Row row, Integer previousColumn, int currentColumn) {
        // Get the current optional cell value
        Optional<Cell> cell = Optional.ofNullable(row.getCell(currentColumn, Row.RETURN_BLANK_AS_NULL));
        // If the cell has a value, attempt to retrieve the next cell using the next column
        if (cell.isPresent()) {
            return findLastColumnWithData(row, currentColumn, currentColumn + 1);
        }
        // Otherwise return the previous column
        return Optional.ofNullable(previousColumn);
    }

    private static String getStringValue(Cell cell) {
        String value = "";
        switch (cell.getCellType()) {
            case Cell.CELL_TYPE_NUMERIC:
                value = String.valueOf(cell.getNumericCellValue());
                break;
            case Cell.CELL_TYPE_STRING:
                value = cell.getStringCellValue();
        }
        return value;
    }
}
