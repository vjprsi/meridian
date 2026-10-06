package io.meridian.filegateway.domain;

import java.nio.charset.StandardCharsets;

/**
 * Simulated antivirus engine. Detects the EICAR standard test file (and Windows executables, which partners
 * never legitimately send) so quarantine flows can be exercised end to end. A production deployment plugs a
 * real engine (ClamAV / ICAP) behind the same contract.
 */
public final class VirusScanner {
    /** The EICAR test string, assembled so this source file itself does not trigger scanners. */
    public static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private VirusScanner() {}

    public record Result(String status, String detail) {
        public boolean infected() {
            return "INFECTED".equals(status);
        }
    }

    public static Result scan(byte[] content) {
        if (content == null) {
            return new Result("ERROR", "No content to scan");
        }
        String text = new String(content, StandardCharsets.ISO_8859_1);
        if (text.contains(EICAR)) {
            return new Result("INFECTED", "Eicar-Test-Signature");
        }
        if (content.length > 2 && content[0] == 'M' && content[1] == 'Z') {
            return new Result("INFECTED", "Suspicious.PE.Executable");
        }
        return new Result("CLEAN", null);
    }
}
