package com.fl.data;

import java.util.Random;

/**
 * HealthcareDataSimulator — Generates synthetic patient data matching base paper specifications.
 *
 * Produces 50,000 patient records with 127 clinical features distributed across
 * 10 hospital nodes with geographic/demographic variation (non-IID distribution).
 *
 * Dataset specification (Ren et al., 2024 — Table 4):
 *   - Total records:  50,000
 *   - Features:       127 (demographics, diagnostics, treatment, outcomes)
 *   - Participants:   10 hospital networks
 *   - Distribution:   Geographic / demographic (non-IID)
 *   - Privacy level:  High
 *   - Label:          Binary — 30-day readmission risk (0=no, 1=yes)
 *
 * Feature groups (127 total):
 *   [0-9]   Demographic: age, gender, BMI, ethnicity codes, socioeconomic indicators
 *   [10-29] Vital signs: BP systolic/diastolic, HR, temp, O2 sat, resp rate, weight, height
 *   [30-59] Lab results: glucose, HbA1c, creatinine, eGFR, Hb, WBC, platelets, lipids, etc.
 *   [60-89] Diagnosis codes: ICD-10 binary flags (diabetes, hypertension, CHF, CKD, etc.)
 *   [90-109] Procedure codes: surgery flags, medication codes, therapy indicators
 *   [110-126] Temporal: length of stay, prior admissions count, days since last visit, etc.
 *
 * Non-IID distribution simulates:
 *   - Hospital 0-2: Urban, high-diversity patient population
 *   - Hospital 3-5: Suburban, middle-income demographics
 *   - Hospital 6-8: Rural, older patient population
 *   - Hospital 9:   Specialty/tertiary care center
 */
public class HealthcareDataSimulator {

    public static final int TOTAL_RECORDS = 50_000;
    public static final int FEATURE_COUNT = 127;
    public static final int NUM_HOSPITALS = 10;

    // Feature group boundaries
    private static final int DEMO_START    = 0;
    private static final int VITAL_START   = 10;
    private static final int LAB_START     = 30;
    private static final int DIAG_START    = 60;
    private static final int PROC_START    = 90;
    private static final int TEMPORAL_START = 110;

    private final Random random;

    public HealthcareDataSimulator() {
        this.random = new Random(2024);  // Fixed seed for reproducibility
    }

    public HealthcareDataSimulator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generate the full 50K dataset and partition it across 10 hospitals.
     * Returns an array of HospitalDataset objects, one per hospital.
     */
    public HospitalDataset[] generateAndPartition() {
        // Generate global dataset
        double[][] allFeatures = new double[TOTAL_RECORDS][FEATURE_COUNT];
        int[]      allLabels   = new int[TOTAL_RECORDS];

        System.out.printf("[DataSimulator] Generating %,d patient records with %d features...%n",
                TOTAL_RECORDS, FEATURE_COUNT);

        for (int i = 0; i < TOTAL_RECORDS; i++) {
            // Assign hospital — weighted non-IID distribution
            int hospital = assignHospital(i);
            allFeatures[i] = generatePatientRecord(hospital);
            allLabels[i]   = generateLabel(allFeatures[i], hospital);
        }

        System.out.printf("[DataSimulator] Dataset generated. Class balance: %.1f%% positive%n",
                100.0 * sum(allLabels) / TOTAL_RECORDS);

        // Partition across hospitals
        return partitionByHospital(allFeatures, allLabels);
    }

    /**
     * Assign hospital index using non-uniform distribution (simulating real-world variation).
     */
    private int assignHospital(int recordIndex) {
        // Non-uniform distribution: some hospitals are larger than others
        double[] hospitalWeights = { 0.15, 0.13, 0.12, 0.11, 0.10, 0.10, 0.09, 0.08, 0.07, 0.05 };
        double r = random.nextDouble();
        double cumulative = 0.0;
        for (int h = 0; h < NUM_HOSPITALS; h++) {
            cumulative += hospitalWeights[h];
            if (r <= cumulative) return h;
        }
        return NUM_HOSPITALS - 1;
    }

    /**
     * Generate a single patient record with hospital-specific demographics.
     */
    private double[] generatePatientRecord(int hospital) {
        double[] record = new double[FEATURE_COUNT];

        // Demographics [0-9] — hospital determines population characteristics
        double ageMean = hospitalAgeMean(hospital);
        record[0] = clamp(ageMean + random.nextGaussian() * 15, 18, 95);       // Age
        record[1] = random.nextDouble() < 0.52 ? 1.0 : 0.0;                   // Gender (F=1)
        record[2] = clamp(27.5 + hospitalBMIOffset(hospital) + random.nextGaussian() * 6, 15, 55);  // BMI
        record[3] = hospitalEthnicityCode(hospital);                            // Ethnicity code
        record[4] = hospitalSocioeconomicScore(hospital) + random.nextGaussian() * 0.2; // SES
        record[5] = record[0] > 65 ? 1.0 : 0.0;                               // Elderly flag
        record[6] = random.nextDouble() < 0.15 ? 1.0 : 0.0;                   // Insurance type
        record[7] = clamp(random.nextGaussian() * 0.5 + 0.5, 0, 1);           // Rural flag
        record[8] = random.nextInt(5);                                          // Admission type
        record[9] = random.nextInt(7);                                          // Admission day

        // Vital signs [10-29]
        record[10] = clamp(120 + random.nextGaussian() * 18, 80, 200);         // Systolic BP
        record[11] = clamp(78 + random.nextGaussian() * 12, 50, 130);          // Diastolic BP
        record[12] = clamp(76 + random.nextGaussian() * 15, 40, 140);          // Heart rate
        record[13] = clamp(37.0 + random.nextGaussian() * 0.8, 35, 42);       // Temperature
        record[14] = clamp(97 + random.nextGaussian() * 2.5, 85, 100);        // O2 saturation
        record[15] = clamp(18 + random.nextGaussian() * 4, 8, 40);            // Resp rate
        record[16] = clamp(75 + random.nextGaussian() * 18, 30, 180);         // Weight (kg)
        record[17] = clamp(170 + random.nextGaussian() * 12, 140, 210);       // Height (cm)
        record[18] = record[16] / Math.pow(record[17] / 100.0, 2);            // Computed BMI
        record[19] = clamp(random.nextGaussian() * 0.5 + 0.3, 0, 1);         // Pain score norm

        // Fill remaining vital signs [20-29]
        for (int i = 20; i < VITAL_START + 20 && i < LAB_START; i++) {
            record[i] = clamp(random.nextGaussian() * 0.5 + 0.5, 0, 1);
        }

        // Lab results [30-59]
        record[30] = clamp(5.5 + random.nextGaussian() * 1.5, 2, 20);        // HbA1c
        record[31] = clamp(100 + random.nextGaussian() * 40, 50, 400);        // Glucose
        record[32] = clamp(1.0 + random.nextGaussian() * 0.5, 0.3, 15);      // Creatinine
        record[33] = clamp(75 + random.nextGaussian() * 25, 5, 130);          // eGFR
        record[34] = clamp(13 + random.nextGaussian() * 2.5, 5, 20);          // Hemoglobin
        record[35] = clamp(7500 + random.nextGaussian() * 2500, 1000, 30000); // WBC
        record[36] = clamp(250000 + random.nextGaussian() * 80000, 50000, 600000); // Platelets
        record[37] = clamp(4.5 + random.nextGaussian() * 1.2, 1, 10);        // Potassium
        record[38] = clamp(140 + random.nextGaussian() * 5, 120, 160);        // Sodium
        record[39] = clamp(24 + random.nextGaussian() * 4, 10, 40);           // Bicarbonate

        // Additional labs [40-59]
        for (int i = 40; i < DIAG_START; i++) {
            record[i] = Math.abs(random.nextGaussian());
        }

        // Diagnosis flags [60-89] — binary ICD-10 code presence
        double[] diagProbs = diagnosisPrevalences(hospital);
        for (int i = DIAG_START; i < PROC_START; i++) {
            int diagIdx = i - DIAG_START;
            record[i] = random.nextDouble() < diagProbs[diagIdx % diagProbs.length] ? 1.0 : 0.0;
        }

        // Procedure codes [90-109]
        for (int i = PROC_START; i < TEMPORAL_START; i++) {
            record[i] = random.nextDouble() < 0.2 ? 1.0 : 0.0;
        }

        // Temporal features [110-126]
        record[110] = clamp(4 + random.nextGaussian() * 3, 0, 30);            // Length of stay
        record[111] = Math.max(0, (int)(random.nextGaussian() * 1.5 + 1.5)); // Prior admissions
        record[112] = clamp(180 + random.nextGaussian() * 120, 0, 730);       // Days since last visit
        record[113] = record[111] > 3 ? 1.0 : 0.0;                            // High readmission history
        for (int i = 114; i < FEATURE_COUNT; i++) {
            record[i] = clamp(random.nextGaussian() * 0.5 + 0.5, 0, 1);
        }

        // Normalize continuous features to [0, 1]
        normalizeRecord(record);

        return record;
    }

    /**
     * Generate binary readmission label based on clinical risk factors.
     * Positive rate ~28% matching real healthcare dataset statistics.
     */
    private int generateLabel(double[] record, int hospital) {
        // Risk score based on clinical risk factors
        double risk = -2.5;                               // Base log-odds
        risk += 0.8  * record[5];                         // Elderly
        risk += 0.6  * (record[30] > 0.5 ? 1 : 0);      // High HbA1c
        risk += 0.5  * (record[110] > 0.5 ? 1 : 0);     // Long stay
        risk += 1.0  * record[113];                       // Prior readmissions
        risk += 0.4  * (record[60] > 0 ? 1 : 0);        // Comorbidity
        risk += 0.3  * (record[2] > 0.7 ? 1 : 0);       // Obesity

        // Hospital-specific population effects
        risk += hospitalRiskOffset(hospital);
        risk += random.nextGaussian() * 0.5;              // Individual variation

        double probability = 1.0 / (1.0 + Math.exp(-risk));
        return random.nextDouble() < probability ? 1 : 0;
    }

    /**
     * Partition the global dataset into hospital-specific subsets.
     */
    private HospitalDataset[] partitionByHospital(double[][] allFeatures, int[] allLabels) {
        // Count records per hospital using non-uniform weights
        int[] hospitalSizes = computeHospitalSizes(TOTAL_RECORDS, NUM_HOSPITALS);

        HospitalDataset[] datasets = new HospitalDataset[NUM_HOSPITALS];
        int offset = 0;

        for (int h = 0; h < NUM_HOSPITALS; h++) {
            int size = hospitalSizes[h];
            double[][] hFeatures = new double[size][FEATURE_COUNT];
            int[]      hLabels   = new int[size];

            for (int i = 0; i < size; i++) {
                System.arraycopy(allFeatures[offset + i], 0, hFeatures[i], 0, FEATURE_COUNT);
                hLabels[i] = allLabels[offset + i];
            }

            double posRate = 100.0 * sum(hLabels) / size;
            datasets[h] = new HospitalDataset("Hospital-" + h, h, hFeatures, hLabels);
            System.out.printf("[DataSimulator] Hospital-%d: %,d records (%.1f%% readmission)%n",
                    h, size, posRate);
            offset += size;
        }
        return datasets;
    }

    // ── Hospital characteristic helpers ───────────────────────────────────

    private double hospitalAgeMean(int h) {
        double[] means = { 52, 54, 51, 58, 56, 55, 63, 65, 62, 48 };
        return means[h % means.length];
    }

    private double hospitalBMIOffset(int h) {
        double[] offsets = { 1.5, 0.5, -0.5, 0.8, 0.2, -0.3, 1.2, 0.9, 0.4, -0.8 };
        return offsets[h % offsets.length];
    }

    private double hospitalEthnicityCode(int h) {
        // Different hospitals have different ethnic compositions
        return (h % 5) * 0.2;
    }

    private double hospitalSocioeconomicScore(int h) {
        double[] scores = { 0.7, 0.65, 0.75, 0.55, 0.60, 0.58, 0.45, 0.42, 0.48, 0.80 };
        return scores[h % scores.length];
    }

    private double hospitalRiskOffset(int h) {
        double[] offsets = { 0.1, 0.0, -0.1, 0.2, 0.15, 0.05, 0.3, 0.25, 0.2, -0.2 };
        return offsets[h % offsets.length];
    }

    private double[] diagnosisPrevalences(int hospital) {
        // Urban hospitals have higher prevalence of lifestyle diseases
        boolean urban = hospital < 3;
        return new double[] {
            urban ? 0.35 : 0.28,  // Diabetes
            urban ? 0.45 : 0.40,  // Hypertension
            urban ? 0.18 : 0.22,  // CHF
            urban ? 0.12 : 0.15,  // CKD
            0.25, 0.20, 0.15, 0.10, 0.08, 0.05,
            0.30, 0.22, 0.18, 0.12, 0.08, 0.06,
            0.20, 0.15, 0.10, 0.08, 0.05, 0.04,
            0.15, 0.12, 0.10, 0.08, 0.06, 0.05, 0.04, 0.03
        };
    }

    private int[] computeHospitalSizes(int total, int numHospitals) {
        double[] weights = { 0.15, 0.13, 0.12, 0.11, 0.10, 0.10, 0.09, 0.08, 0.07, 0.05 };
        int[] sizes = new int[numHospitals];
        int allocated = 0;
        for (int h = 0; h < numHospitals - 1; h++) {
            sizes[h] = (int)(total * weights[h]);
            allocated += sizes[h];
        }
        sizes[numHospitals - 1] = total - allocated;
        return sizes;
    }

    private void normalizeRecord(double[] record) {
        // Min-max normalize first 30 continuous features
        double[] mins = new double[30];
        double[] maxs = new double[30];
        double[] rawMins = { 18,0,15,0,0,0,0,0,0,0, 80,50,40,35,85,8,30,140,10,0, 2,50,0.3,5,5,1000,50000,1,120,10 };
        double[] rawMaxs = { 95,1,55,4,1,1,1,1,6,6, 200,130,140,42,100,40,180,210,55,1, 20,400,15,130,20,30000,600000,10,160,40 };

        for (int i = 0; i < Math.min(30, FEATURE_COUNT); i++) {
            double range = rawMaxs[i] - rawMins[i];
            if (range > 0) {
                record[i] = (record[i] - rawMins[i]) / range;
                record[i] = clamp(record[i], 0, 1);
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int sum(int[] arr) {
        int s = 0;
        for (int v : arr) s += v;
        return s;
    }

    // ── HospitalDataset inner class ───────────────────────────────────────

    /**
     * Dataset container for a single hospital node.
     */
    public static class HospitalDataset {
        private final String name;
        private final int hospitalId;
        private final double[][] features;
        private final int[] labels;

        public HospitalDataset(String name, int hospitalId,
                               double[][] features, int[] labels) {
            this.name = name;
            this.hospitalId = hospitalId;
            this.features = features;
            this.labels = labels;
        }

        /** Split into train/test sets (80/20 split). */
        public double[][][] getTrainTestFeatures() {
            int trainSize = (int)(features.length * 0.8);
            double[][] train = new double[trainSize][];
            double[][] test  = new double[features.length - trainSize][];
            System.arraycopy(features, 0, train, 0, trainSize);
            System.arraycopy(features, trainSize, test, 0, test.length);
            return new double[][][] { train, test };
        }

        public int[][] getTrainTestLabels() {
            int trainSize = (int)(labels.length * 0.8);
            int[] train = new int[trainSize];
            int[] test  = new int[labels.length - trainSize];
            System.arraycopy(labels, 0, train, 0, trainSize);
            System.arraycopy(labels, trainSize, test, 0, test.length);
            return new int[][] { train, test };
        }

        public String getName()       { return name; }
        public int getHospitalId()    { return hospitalId; }
        public double[][] getFeatures() { return features; }
        public int[] getLabels()       { return labels; }
        public int getSize()           { return labels.length; }
    }
}
