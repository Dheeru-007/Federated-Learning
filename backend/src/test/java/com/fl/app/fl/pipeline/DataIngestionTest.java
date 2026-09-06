package com.fl.app.fl.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DataIngestion CSV parser.
 */
class DataIngestionTest {

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid 3-feature CSV is parsed correctly")
    void parse_validCsv_correctDimensions() {
        String csv = "f1,f2,f3,label\n"
                   + "1.0,2.0,3.0,1\n"
                   + "4.0,5.0,6.0,0\n"
                   + "7.0,8.0,9.0,1\n";

        var result = DataIngestion.parse(csv);

        assertEquals(3, result.rowCount(),     "Should have 3 data rows");
        assertEquals(3, result.featureCount(), "Should have 3 features (last column is label)");
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, result.features()[0], 1e-12);
        assertArrayEquals(new int[]{1, 0, 1},           result.labels());
    }

    @Test
    @DisplayName("Header row is skipped — row count excludes header")
    void parse_headerSkipped() {
        String csv = "a,b,label\n1.0,2.0,0\n";
        var result = DataIngestion.parse(csv);

        assertEquals(1, result.rowCount());
    }

    @Test
    @DisplayName("Blank lines in CSV are ignored")
    void parse_blankLinesIgnored() {
        String csv = "x,y,label\n\n1.0,2.0,1\n\n3.0,4.0,0\n\n";
        var result = DataIngestion.parse(csv);

        assertEquals(2, result.rowCount());
    }

    @Test
    @DisplayName("Negative feature values are accepted")
    void parse_negativeValues_accepted() {
        String csv = "a,b,label\n-1.5,-0.5,0\n";
        var result = DataIngestion.parse(csv);

        assertEquals(-1.5, result.features()[0][0], 1e-12);
        assertEquals(-0.5, result.features()[0][1], 1e-12);
    }

    // ─── Error cases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty CSV (header only) throws IllegalArgumentException")
    void parse_emptyData_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> DataIngestion.parse("f1,f2,label\n"),
            "CSV with no data rows must throw");
    }

    @Test
    @DisplayName("Inconsistent feature count throws exception")
    void parse_inconsistentFeatureCount_throws() {
        String csv = "a,b,label\n1.0,2.0,1\n3.0,4.0,5.0,0\n"; // row 2 has extra column
        assertThrows(RuntimeException.class,
            () -> DataIngestion.parse(csv),
            "Inconsistent feature count must throw");
    }

    @Test
    @DisplayName("Label out of range (not 0 or 1) throws IllegalArgumentException")
    void parse_labelOutOfRange_throws() {
        String csv = "a,b,label\n1.0,2.0,2\n";  // label=2 is invalid
        assertThrows(RuntimeException.class,
            () -> DataIngestion.parse(csv),
            "Label values other than 0 or 1 must throw");
    }

    @Test
    @DisplayName("Non-numeric feature value throws a RuntimeException")
    void parse_nonNumericFeature_throws() {
        String csv = "a,b,label\nABC,2.0,1\n";
        assertThrows(RuntimeException.class,
            () -> DataIngestion.parse(csv),
            "Non-numeric feature must cause a parse exception");
    }

    @Test
    @DisplayName("Single-feature CSV is supported")
    void parse_singleFeature_works() {
        String csv = "x,label\n0.5,1\n0.3,0\n";
        var result = DataIngestion.parse(csv);

        assertEquals(1, result.featureCount());
        assertEquals(2, result.rowCount());
    }
}
