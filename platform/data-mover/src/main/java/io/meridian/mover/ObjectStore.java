package io.meridian.mover;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Minimal S3-compatible client (AWS S3, SeaweedFS, MinIO) on SigV4 presigned URLs, path-style: list a prefix
 * (ListObjectsV2), head, get, put. Enough for copying a tenant's document prefix between cell buckets; in AWS a
 * server-side {@code CopyObject} / S3 Batch Operations job is the faster equivalent (see docs/MULTI_REGION.md).
 */
public class ObjectStore {

    /** One listed object; {@code etag} without quotes (MD5 hex for single-part uploads). */
    public record Obj(String key, long size, String etag) {}

    public record Page(List<Obj> objects, boolean truncated) {}

    private final String endpoint;
    private final String bucket;
    private final SigV4Presigner presigner;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();

    public ObjectStore(MoverProperties.S3 s3) {
        this.endpoint = s3.endpoint().endsWith("/") ? s3.endpoint().substring(0, s3.endpoint().length() - 1) : s3.endpoint();
        this.bucket = s3.bucket();
        this.presigner = new SigV4Presigner(s3.accessKey(), s3.secretKey(), s3.region() == null ? "us-east-1" : s3.region());
    }

    public String bucket() {
        return bucket;
    }

    /** Up to 1,000 keys under {@code prefix} after {@code startAfter} (null = from the start), in key order. */
    public Page list(String prefix, String startAfter) {
        Map<String, String> q = new TreeMap<>();
        q.put("list-type", "2");
        q.put("prefix", prefix);
        q.put("max-keys", "1000");
        if (startAfter != null) {
            q.put("start-after", startAfter);
        }
        HttpResponse<byte[]> r = send("GET", presigner.presign("GET", endpoint, "/" + bucket, q, 300, Instant.now()), null);
        if (r.statusCode() != 200) {
            throw new MoverHttp.TransientException("object store list " + bucket + "/" + prefix + " answered " + r.statusCode());
        }
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(r.body()));
            List<Obj> out = new ArrayList<>();
            NodeList contents = doc.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                Element c = (Element) contents.item(i);
                out.add(new Obj(text(c, "Key"), Long.parseLong(text(c, "Size")), unquote(text(c, "ETag"))));
            }
            NodeList t = doc.getElementsByTagName("IsTruncated");
            boolean truncated = t.getLength() > 0 && "true".equalsIgnoreCase(t.item(0).getTextContent().trim());
            return new Page(out, truncated);
        } catch (Exception e) {
            throw new MoverHttp.TransientException("unreadable list response: " + e.getMessage());
        }
    }

    public Optional<Obj> head(String key) {
        HttpResponse<byte[]> r = send("HEAD", presign("HEAD", key), null);
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw new MoverHttp.TransientException("object store head " + key + " answered " + r.statusCode());
        }
        return Optional.of(new Obj(key, r.headers().firstValueAsLong("Content-Length").orElse(-1),
                unquote(r.headers().firstValue("ETag").orElse(""))));
    }

    public byte[] get(String key) {
        HttpResponse<byte[]> r = send("GET", presign("GET", key), null);
        if (r.statusCode() != 200) {
            throw new MoverHttp.TransientException("object store get " + key + " answered " + r.statusCode());
        }
        return r.body();
    }

    public void put(String key, byte[] content) {
        HttpResponse<byte[]> r = send("PUT", presign("PUT", key), content);
        if (r.statusCode() >= 300) {
            throw new MoverHttp.TransientException("object store put " + key + " answered " + r.statusCode());
        }
    }

    private String presign(String method, String key) {
        return presigner.presign(method, endpoint, "/" + bucket + "/" + key, Map.of(), 300, Instant.now());
    }

    private HttpResponse<byte[]> send(String method, String url, byte[] body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        b = switch (method) {
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
            case "HEAD" -> b.method("HEAD", HttpRequest.BodyPublishers.noBody());
            default -> b.GET();
        };
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new MoverHttp.TransientException("object store unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MoverHttp.TransientException("interrupted");
        }
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent();
    }

    private static String unquote(String s) {
        return s == null ? "" : s.replace("\"", "").replace("&quot;", "");
    }
}
