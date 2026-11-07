#!/usr/bin/env python3
"""W1 code: Colombian festivos in reference-data HolidayGenerator + CO national account numbering. Idempotent."""
import io, sys

def patch(path, edits):
    s = io.open(path, encoding="utf-8").read()
    for tag, old, new, guard in edits:
        if guard in s:
            print(f"  [{tag}] already"); continue
        if old not in s:
            print(f"  [{tag}] ANCHOR NOT FOUND in {path}"); sys.exit(2)
        s = s.replace(old, new, 1); print(f"  [{tag}] applied")
    io.open(path, "w", encoding="utf-8").write(s)

HG = "services/reference-data-service/src/main/java/io/meridian/referencedata/domain/HolidayGenerator.java"
patch(HG, [
    ("sources", 'Set.of("TARGET2", "WESTERN_CHRISTIAN", "US_FEDERAL")',
     'Set.of("TARGET2", "WESTERN_CHRISTIAN", "US_FEDERAL", "CO")', '"US_FEDERAL", "CO"'),
    ("infer", '''        if (c.startsWith("TARGET")) {
            return "TARGET2";
        }''', '''        if (c.startsWith("TARGET")) {
            return "TARGET2";
        }
        if (c.equals("CO") || c.startsWith("CO-") || c.startsWith("CO_") || c.equals("BREB") || c.equals("BRE-B")
                || c.equals("TRANSFIYA") || c.equals("ACH_CO") || c.equals("ACH-CO") || c.equals("CENIT")) {
            return "CO";
        }''', 'return "CO";'),
    ("switch", '            case "US_FEDERAL" -> usFederal(year);',
     '            case "US_FEDERAL" -> usFederal(year);\n            case "CO" -> colombia(year);', 'case "CO" -> colombia(year);'),
    ("method", '    private static GeneratedHoliday observed(LocalDate date, String name) {', '''    /**
     * Colombian national holidays (festivos) per Ley 51 de 1983 ("Ley Emiliani"): six fixed-date holidays, seven
     * feasts moved to the following Monday, Holy Thursday and Good Friday (fixed, Easter-based) and the Ascension,
     * Corpus Christi and Sacred Heart feasts (Easter-based, moved to Monday). 18 business-day closures a year; used by
     * the Bre-B, Transfiya, ACH Colombia and branch calendars of a Colombian tenant.
     */
    static List<GeneratedHoliday> colombia(int year) {
        LocalDate easter = easterSunday(year);
        List<GeneratedHoliday> out = new ArrayList<>();
        out.add(new GeneratedHoliday(LocalDate.of(year, 1, 1), "A\\u00f1o Nuevo", "PUBLIC"));
        out.add(emiliani(LocalDate.of(year, 1, 6), "D\\u00eda de los Reyes Magos"));
        out.add(emiliani(LocalDate.of(year, 3, 19), "D\\u00eda de San Jos\\u00e9"));
        out.add(new GeneratedHoliday(easter.minusDays(3), "Jueves Santo", "PUBLIC"));
        out.add(new GeneratedHoliday(easter.minusDays(2), "Viernes Santo", "PUBLIC"));
        out.add(new GeneratedHoliday(LocalDate.of(year, 5, 1), "D\\u00eda del Trabajo", "PUBLIC"));
        out.add(emiliani(easter.plusDays(39), "Ascensi\\u00f3n del Se\\u00f1or"));
        out.add(emiliani(easter.plusDays(60), "Corpus Christi"));
        out.add(emiliani(easter.plusDays(68), "Sagrado Coraz\\u00f3n de Jes\\u00fas"));
        out.add(emiliani(LocalDate.of(year, 6, 29), "San Pedro y San Pablo"));
        out.add(new GeneratedHoliday(LocalDate.of(year, 7, 20), "D\\u00eda de la Independencia", "PUBLIC"));
        out.add(new GeneratedHoliday(LocalDate.of(year, 8, 7), "Batalla de Boyac\\u00e1", "PUBLIC"));
        out.add(emiliani(LocalDate.of(year, 8, 15), "La Asunci\\u00f3n de la Virgen"));
        out.add(emiliani(LocalDate.of(year, 10, 12), "D\\u00eda de la Raza"));
        out.add(emiliani(LocalDate.of(year, 11, 1), "D\\u00eda de Todos los Santos"));
        out.add(emiliani(LocalDate.of(year, 11, 11), "Independencia de Cartagena"));
        out.add(new GeneratedHoliday(LocalDate.of(year, 12, 8), "Inmaculada Concepci\\u00f3n", "PUBLIC"));
        out.add(new GeneratedHoliday(LocalDate.of(year, 12, 25), "Navidad", "PUBLIC"));
        out.sort(java.util.Comparator.comparing(GeneratedHoliday::date));
        return out;
    }

    /** Ley Emiliani: the holiday is kept on the date when that is a Monday, else on the following Monday. */
    private static GeneratedHoliday emiliani(LocalDate date, String name) {
        return new GeneratedHoliday(date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)), name, "PUBLIC");
    }

    private static GeneratedHoliday observed(LocalDate date, String name) {''', "static List<GeneratedHoliday> colombia(int year)"),
])

NR = "services/deposit-service/src/main/java/io/meridian/deposit/domain/NumberingRule.java"
patch(NR, [
    ("co-format", '''            "NG", new NationalFormat(3, 0, "{serial}{check}", 9, CheckDigit.NUBAN));''',
     '''            "NG", new NationalFormat(3, 0, "{serial}{check}", 9, CheckDigit.NUBAN),
            // CO: no IBAN; 3-digit entity code (ACH Colombia / Superfinanciera participant code) and 3-digit office
            // code; account number = office (3) + serial (7) + Luhn over entity + office + serial = 11 digits.
            "CO", new NationalFormat(3, 3, "{branch}{serial}{check}", 7, CheckDigit.LUHN));''', '"CO", new NationalFormat'),
])
print("W1 code patched")
