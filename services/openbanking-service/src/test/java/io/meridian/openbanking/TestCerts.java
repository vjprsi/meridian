package io.meridian.openbanking;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import io.meridian.openbanking.domain.Psd2Certificate;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

/** Builds eIDAS-style test certificates (PSD2 QCStatement) and signed software statements. */
public final class TestCerts {
    public static final Map<String, String> ROLE_OIDS = Map.of(
            "PSP_AS", "0.4.0.19495.1.1", "PSP_PI", "0.4.0.19495.1.2", "PSP_AI", "0.4.0.19495.1.3", "PSP_IC", "0.4.0.19495.1.4");

    private static long serial = 1000;

    private TestCerts() {}

    public record Issued(KeyPair keys, String pem) {}

    public static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A certificate valid from {@code from} to {@code to}; {@code roles} null = no PSD2 QCStatement. */
    public static synchronized Issued issue(String orgId, List<String> roles, String ncaId, Instant from, Instant to) {
        KeyPair keys = rsa();
        try {
            V3TBSCertificateGenerator g = new V3TBSCertificateGenerator();
            g.setSerialNumber(new ASN1Integer(BigInteger.valueOf(++serial)));
            g.setIssuer(new X500Name("CN=Meridian Test QTSP, O=Test QTSP, C=GB"));
            X500NameBuilder subject = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.C, "GB")
                    .addRDN(BCStyle.O, "Acme Fintech Ltd");
            if (orgId != null) {
                subject.addRDN(new ASN1ObjectIdentifier("2.5.4.97"), new DERUTF8String(orgId));
            }
            subject.addRDN(BCStyle.CN, "acme-" + serial);
            g.setSubject(subject.build());
            g.setStartDate(new Time(Date.from(from)));
            g.setEndDate(new Time(Date.from(to)));
            g.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keys.getPublic().getEncoded()));
            AlgorithmIdentifier alg = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
            g.setSignature(alg);
            if (roles != null) {
                ASN1EncodableVector roleSeq = new ASN1EncodableVector();
                for (String r : roles) {
                    roleSeq.add(new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                            new ASN1ObjectIdentifier(ROLE_OIDS.get(r)), new DERUTF8String(r)}));
                }
                DERSequence psd2 = new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                        new ASN1ObjectIdentifier(Psd2Certificate.PSD2_QC_TYPE_OID),
                        new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                                new DERSequence(roleSeq), new DERUTF8String("Financial Conduct Authority"),
                                new DERUTF8String(ncaId)})});
                DERSequence compliance = new DERSequence(new ASN1ObjectIdentifier("0.4.0.1862.1.1"));
                ExtensionsGenerator ext = new ExtensionsGenerator();
                ext.addExtension(new ASN1ObjectIdentifier(Psd2Certificate.QC_STATEMENTS_OID), false,
                        new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {compliance, psd2}));
                g.setExtensions(ext.generate());
            }
            TBSCertificate tbs = g.generateTBSCertificate();
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(keys.getPrivate());
            sig.update(tbs.getEncoded(ASN1Encoding.DER));
            ASN1EncodableVector cert = new ASN1EncodableVector();
            cert.add(tbs);
            cert.add(alg);
            cert.add(new DERBitString(sig.sign()));
            return new Issued(keys, Psd2Certificate.toPem(new DERSequence(cert).getEncoded(ASN1Encoding.DER)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A self-signed CA (trust anchor) and a leaf it issued, as PEM (SEC-27 tests). */
    public static synchronized String[] caAndLeaf() {
        try {
            KeyPair ca = rsa();
            KeyPair leaf = rsa();
            String caPem = sign(ca, "CN=Test QTSP Root, O=Test QTSP, C=GB", "CN=Test QTSP Root, O=Test QTSP, C=GB", ca);
            String leafPem = sign(leaf, "CN=Test QTSP Root, O=Test QTSP, C=GB", "CN=acme-leaf, O=Acme Fintech Ltd, C=GB", ca);
            return new String[] {caPem, leafPem};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(KeyPair subjectKeys, String issuer, String subject, KeyPair signer) throws Exception {
        V3TBSCertificateGenerator g = new V3TBSCertificateGenerator();
        g.setSerialNumber(new ASN1Integer(BigInteger.valueOf(++serial)));
        g.setIssuer(new X500Name(issuer));
        g.setSubject(new X500Name(subject));
        g.setStartDate(new Time(Date.from(Instant.now().minusSeconds(3600))));
        g.setEndDate(new Time(Date.from(Instant.now().plusSeconds(86_400))));
        g.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(subjectKeys.getPublic().getEncoded()));
        AlgorithmIdentifier alg = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
        g.setSignature(alg);
        TBSCertificate tbs = g.generateTBSCertificate();
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(signer.getPrivate());
        sig.update(tbs.getEncoded(ASN1Encoding.DER));
        ASN1EncodableVector cert = new ASN1EncodableVector();
        cert.add(tbs);
        cert.add(alg);
        cert.add(new DERBitString(sig.sign()));
        return Psd2Certificate.toPem(new DERSequence(cert).getEncoded(ASN1Encoding.DER));
    }

    public static Issued valid(String orgId, List<String> roles, String ncaId) {
        Instant now = Instant.now();
        return issue(orgId, roles, ncaId, now.minusSeconds(86_400), now.plusSeconds(365L * 86_400));
    }

    public static String ssa(KeyPair keys, Map<String, Object> claims) {
        try {
            JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.PS256), new Payload(claims));
            jws.sign(new RSASSASigner(keys.getPrivate()));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
