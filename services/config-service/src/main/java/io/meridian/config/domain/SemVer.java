package io.meridian.config.domain;

import io.meridian.common.error.ApiException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic version (major.minor.patch[-pre]) as used by country packs; pre-releases sort before releases. */
public record SemVer(int major, int minor, int patch, String pre) implements Comparable<SemVer> {
    private static final Pattern P = Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?$");

    public static SemVer parse(String s) {
        Matcher m = s == null ? null : P.matcher(s.trim());
        if (m == null || !m.matches()) {
            throw ApiException.unprocessable("invalid_version", "Not a semantic version: " + s);
        }
        return new SemVer(Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), m.group(4));
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c == 0) {
            if (pre == null && o.pre == null) {
                return 0;
            }
            if (pre == null) {
                return 1;
            }
            if (o.pre == null) {
                return -1;
            }
            return pre.compareTo(o.pre);
        }
        return c;
    }
}
