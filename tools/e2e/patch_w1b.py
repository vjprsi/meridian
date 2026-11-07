import io,sys
def patch(path, edits):
    s=io.open(path,encoding="utf-8").read()
    for tag,old,new,guard in edits:
        if guard in s: print(f"  [{tag}] already"); continue
        if old not in s: print(f"  [{tag}] ANCHOR NOT FOUND"); sys.exit(2)
        s=s.replace(old,new,1); print(f"  [{tag}] applied")
    io.open(path,"w",encoding="utf-8").write(s)
patch("services/reference-data-service/src/main/java/io/meridian/referencedata/domain/HolidayGenerator.java",[
 ("breb-not-co",' || c.equals("BREB") || c.equals("BRE-B")\n                || c.equals("TRANSFIYA")',' || c.equals("TRANSFIYA")','c.startsWith("CO_") || c.equals("TRANSFIYA")')])
patch("services/reference-data-service/src/main/java/io/meridian/referencedata/application/CalendarService.java",[
 ("auto-import",'''        changed(UUID.fromString(r.get("id").asString()), "CALENDAR_CREATED", null);
        return row;''','''        UUID id = UUID.fromString(r.get("id").asString());
        changed(id, "CALENDAR_CREATED", null);
        // A calendar whose code maps to a built-in rule feed (TARGET2, US-*, CO-*, ACH-CO ...) starts populated:
        // the current and the next two years are generated; later years come from the import action.
        if (HolidayGenerator.inferSource(r.path("code").asString()) != null) {
            int year = java.time.LocalDate.now(java.time.ZoneOffset.UTC).getYear();
            for (int y = year; y <= year + 2; y++) {
                importHolidays(id, y, null);
            }
        }
        return row;''','starts populated')])
print("done")
