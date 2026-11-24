package tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.poi.*;

public class excelReader {

    try (InputStream inp = new FileInputStream("workbook.xls")) {
//InputStream inp = new FileInputStream("workbook.xlsx");
    Workbook wb = WorkbookFactory.create(inp);
    Sheet sheet = wb.getSheetAt(0);
    Row row = sheet.getRow(2);
    Cell cell = row.getCell(3);
    if (cell == null)
        cell = row.createCell(3);
    cell.setCellType(CellType.STRING);
    cell.setCellValue("a test");
    // Write the output to a file
    try (OutputStream fileOut = new FileOutputStream("workbook.xls")) {
        wb.write(fileOut);
    }
}

    public static void main(String[] args) {
        File excel = GFG.getExcelFile();

        System.out.println("Selected file: " + excel.getAbsolutePath());
    }
}
