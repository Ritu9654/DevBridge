package com.devbridge.api;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Server-side export endpoint. Handles Excel (.xlsx) because real .xlsx is a
 * ZIP of XML files and hand-rolling is painful — Apache POI does it right.
 * CSV and JSON exports are generated client-side (no roundtrip needed).
 */
@RestController
@RequestMapping("/api/sql/export")
public class SqlExportController {

    private static final Logger log = LoggerFactory.getLogger(SqlExportController.class);

    private static final MediaType XLSX_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @PostMapping("/xlsx")
    public ResponseEntity<?> exportXlsx(@RequestBody ExportRequest req) {
        if (req == null || req.columns() == null || req.rows() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "columns and rows are required"));
        }
        try {
            byte[] bytes = buildXlsx(req.columns(), req.rows());
            String filename = "sql-result-" + LocalDateTime.now().format(STAMP) + ".xlsx";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(XLSX_TYPE);
            headers.setContentDispositionFormData("attachment", filename);
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (IOException e) {
            log.warn("XLSX export failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to build .xlsx: " + e.getMessage()));
        }
    }

    private byte[] buildXlsx(List<String> columns, List<Map<String, Object>> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("SQL Result");

            // Bold header row
            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(columns.get(c));
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> rowData = rows.get(r);
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = row.createCell(c);
                    Object v = rowData.get(columns.get(c));
                    writeCell(cell, v);
                }
            }

            // Fixed reasonable column width — 20 chars. Avoids POI's slow autoSizeColumn.
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, 20 * 256);
            }
            // Freeze the header so scrolling keeps it visible
            sheet.createFreezePane(0, 1);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private void writeCell(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(v.toString());
        }
    }

    public record ExportRequest(List<String> columns, List<Map<String, Object>> rows) {}
}
