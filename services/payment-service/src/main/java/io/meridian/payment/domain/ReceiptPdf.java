package io.meridian.payment.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a one-page PDF 1.4 payment advice (Helvetica, WinAnsi text) without external libraries: catalog, pages,
 * page, font and content stream objects with a correct cross-reference table.
 */
public final class ReceiptPdf {
    public record Row(String label, String value) {}

    private ReceiptPdf() {}

    public static byte[] render(String title, String subtitle, List<Row> rows, String footer) {
        StringBuilder content = new StringBuilder();
        content.append("BT /F1 18 Tf 50 790 Td (").append(esc(title)).append(") Tj ET\n");
        content.append("BT /F1 10 Tf 50 770 Td (").append(esc(subtitle)).append(") Tj ET\n");
        content.append("0.6 w 50 760 m 545 760 l S\n");
        int y = 735;
        for (Row r : rows) {
            content.append("BT /F2 10 Tf 50 ").append(y).append(" Td (").append(esc(r.label())).append(") Tj ET\n");
            content.append("BT /F1 10 Tf 230 ").append(y).append(" Td (").append(esc(r.value())).append(") Tj ET\n");
            y -= 18;
        }
        content.append("0.6 w 50 ").append(y + 6).append(" m 545 ").append(y + 6).append(" l S\n");
        content.append("BT /F1 8 Tf 50 ").append(y - 12).append(" Td (").append(esc(footer)).append(") Tj ET\n");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);

        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        offsets.add(out.size());
        write(out, "6 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
        out.writeBytes(stream);
        write(out, "\nendstream\nendobj\n");
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 7\n0000000000 65535 f \n");
        for (int off : offsets) {
            x.append(String.format("%010d 00000 n \n", off));
        }
        x.append("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** PDF literal string escaping; non Latin-1 characters are transliterated or dropped. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        String a = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
        return a.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
