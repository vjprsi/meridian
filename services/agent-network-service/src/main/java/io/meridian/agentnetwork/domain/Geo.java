package io.meridian.agentnetwork.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;

/** Great-circle distances (Haversine, mean Earth radius) and bounding boxes for nearby searches. */
public final class Geo {
    public static final double EARTH_RADIUS_M = 6_371_000d;
    public static final int DEFAULT_RADIUS_M = 5_000;
    public static final int MAX_RADIUS_M = 50_000;
    private static final double METRES_PER_DEGREE = 111_320d;

    private Geo() {}

    public static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** {minLat, maxLat, minLon, maxLon} enclosing a circle (pre-filter for the exact Haversine test). */
    public static double[] boundingBox(double lat, double lon, int radiusM) {
        double dLat = radiusM / METRES_PER_DEGREE;
        double cos = Math.max(0.01, Math.cos(Math.toRadians(lat)));
        double dLon = Math.min(180, radiusM / (METRES_PER_DEGREE * cos));
        return new double[] {lat - dLat, lat + dLat, lon - dLon, lon + dLon};
    }

    public static int radius(Integer requested) {
        int r = requested == null ? DEFAULT_RADIUS_M : requested;
        if (r <= 0 || r > MAX_RADIUS_M) {
            throw ApiException.badRequest("invalid_radius", "radius_m must be between 1 and " + MAX_RADIUS_M);
        }
        return r;
    }

    /** Latitude and longitude must be given together and lie within range. */
    public static void validate(BigDecimal lat, BigDecimal lon) {
        if ((lat == null) != (lon == null)) {
            throw ApiException.unprocessable("invalid_location", "latitude and longitude must be given together");
        }
        if (lat != null && (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lon.abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw ApiException.unprocessable("invalid_location", "latitude must be -90..90 and longitude -180..180");
        }
    }
}
