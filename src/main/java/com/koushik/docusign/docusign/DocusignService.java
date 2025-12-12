package com.koushik.docusign.docusign;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * DocuSign service using direct REST API calls (no SDK) for Jira plugin compatibility.
 * This avoids Jakarta/Jersey 3.x dependencies that conflict with Jira's javax.ws.rs stack.
 */
public class DocusignService {

    private final String basePath = "https://demo.docusign.net/restapi";
    private final String authUrl = "https://account-d.docusign.com/oauth/token"; // Demo environment
    
    // DocuSign credentials
    private final String integrationKey = "37a35ef8-eb8d-413a-b34c-b4a95eda8c8e";
    private final String userId = "f900506f-da7a-4b14-8d6a-283775b775f2";
    private final String accountId = "7392990d-1ae4-4c3e-a17a-408bba9394af";
    
    private final String privateKey = "-----BEGIN RSA PRIVATE KEY-----\n" +
            "MIIEpAIBAAKCAQEA0pz97TAyULQAH9bqZ0aqFDqJ/gIUqSz6eYrmFeSyUXdUZ5DF\n" +
            "NZk02gctpSGiBD1JOnXv02tTI9g8ICNh6DBPV/pUGl2GIqCB8L5b4fzrzfMhu+Er\n" +
            "X4JffS3m4CH0wfHnRCiQGDfHiItZvMixtGK7pz4cGr8sWi2hwHVrk48hNxXJUs9C\n" +
            "BBfSvGkBXmGGNjmqxmPBOjSCRoXZwSTdq29gDxHutV36/+Hk2XRDbRmTkWkbcTwn\n" +
            "E3OfFjjjYO1fWhcx5f4EPbMY/+9MkCfdk6fYvDvq3pEu45HFJEJHPluWmdd2aqUu\n" +
            "lHkFWOBkGvwNowIwEWo08CaBcjfi+kgTLknHDwIDAQABAoIBABmV480gKUSfkVJM\n" +
            "3/362ieJ8wCODSd+JNGGG6x2M2ltJy5LqoF34rFH5PYmD1IXheFZuXBEHf4BQ1Ce\n" +
            "K8MytzjXWsc3LFfhiteNsIjUGmtTCXqTAJtiMap53I3G4j57Xh5sFRE0GYPPde/W\n" +
            "q6vfwK8w/uYK6l7sIzXHrfFnll/kB7EwZOb8YXMm6gpl2tGPPWvlseSdpmQjizM6\n" +
            "m+y5+vQBwUeIss+pVqMmB0o9RleeF04ZA3YnUP1Snd3UxeHaLAPiryQHkunJiCL7\n" +
            "n2K60/11qRaXaTKPp99jhnH1CbO9u8orSR4hK3QBWopF2RNL9cElytqce7yCD4n7\n" +
            "7rZXd7kCgYEA8iNAb3tEeVq1NiWggu/viJx+TTT4OINwG9N/KNWCXGhJod49w2t7\n" +
            "KUqEV7QqQfRpf0QPu1uQ38bseVwfSJhgS3pyHUzHm1pO6NcIZ2nG8UkgMfMZhvh4\n" +
            "tFPJLhxzGpwzYgEoFs8w98m2KAZpYXZql0rvLOCXAH5HtBV/n0d8escCgYEA3qu4\n" +
            "ffSNC3xYzkhN3BSoJ4+tq+U5XKCqVYuU2jRqZVqmPTQusRS1wmhg2aittFTxHFrz\n" +
            "nhVVpc57sQzfE2iYiMBhYGozX+RpEdtPplHvvIemfA8QD2Sp5I1J+xF+YneOxraX\n" +
            "4nX2aM8L7Wji4y+xqBPX6RETuptLSslXASYHSXkCgYEApdmhc/qRrzGDN4BUTfs8\n" +
            "LW1LUWS7tDHLIzQdQAHmVZcVACsyUN0YsfKZbV05KI3ZiNM8l08jjzM4m/OOdfHw\n" +
            "2yIWcZ06h102+WL4HaUlH/W/eJcTYBBm1NUi0lOoP4zH4RP7uovV9ZMTEp05pwkt\n" +
            "/0zTQADhTPQx9tZW4OldCNcCgYBrOPlf/ZClhT0mJ/8GCRRn6HHSolCa3+rlwo7s\n" +
            "++x33czLEAOj1bsoYCay6NysR3LLGqjQ6KkTbHh3ayFIMUeyIiFB0iHm/Q/zP039\n" +
            "Yts0R4XNm1s6bli466hCM8xOEhA4c9hzfiYnlfvCWI1YpLDBpLyFSGndo8X/vzAc\n" +
            "J3m+0QKBgQDpB2TsuF+0MQrPFRtG7gRrYA/FbZ27k9m1MjvQju7S7Iy9t7SEZYKm\n" +
            "jrZ3/Z+nsdUN3G9MyrovZs2cYvpZwvgYyr6YGiG5WEeTgrL+rUtI8bw0VK3hACEe\n" +
            "M7y7hDhyqomOVNasrwC4/dBMvaFBWHU6pe28YoNUh6DRI/rf/guynw==\n" +
            "-----END RSA PRIVATE KEY-----";

    private final HttpClient httpClient = HttpClients.createDefault();
    
    /**
     * Converts PEM-formatted RSA private key to RSAPrivateKey object
     */
    private RSAPrivateKey getPrivateKey() throws Exception {
        String privateKeyContent = privateKey
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
    }

    /**
     * Generates JWT token for DocuSign authentication
     */
    private String generateJWT() throws Exception {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600); // 1 hour expiration
        
        Algorithm algorithm = Algorithm.RSA256(null, getPrivateKey());
        
        return JWT.create()
                .withIssuer(integrationKey)
                .withSubject(userId)
                .withIssuedAt(java.util.Date.from(now))
                .withExpiresAt(java.util.Date.from(expiresAt))
                .withAudience("account-d.docusign.com")
                .withClaim("scope", "signature impersonation")
                .sign(algorithm);
    }

    /**
     * Gets access token using JWT authentication
     */
    private String getAccessToken() throws Exception {
        String jwt = generateJWT();
        
        HttpPost request = new HttpPost(authUrl);
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        
        StringEntity params = new StringEntity(
            "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + jwt,
            StandardCharsets.UTF_8
        );
        request.setEntity(params);
        
        HttpResponse response = httpClient.execute(request);
        String responseBody = EntityUtils.toString(response.getEntity());
        
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new RuntimeException("Failed to get access token: " + responseBody);
        }
        
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        return jsonResponse.get("access_token").getAsString();
    }

    /**
     * Document model for DocuSign envelope
     */
    public static class DocuSignDocument {
        private String documentBase64;
        private String name;
        private String documentId;
        
        public DocuSignDocument(String documentBase64, String name, String documentId) {
            this.documentBase64 = documentBase64;
            this.name = name;
            this.documentId = documentId;
        }
        
        public String getDocumentBase64() { return documentBase64; }
        public String getName() { return name; }
        public String getDocumentId() { return documentId; }
    }

    /**
     * Signer model for DocuSign envelope
     */
    public static class DocuSignSigner {
        private String email;
        private String name;
        private String recipientId;
        private String routingOrder;
        
        public DocuSignSigner(String email, String name, String recipientId, String routingOrder) {
            this.email = email;
            this.name = name;
            this.recipientId = recipientId;
            this.routingOrder = routingOrder;
        }
        
        public String getEmail() { return email; }
        public String getName() { return name; }
        public String getRecipientId() { return recipientId; }
        public String getRoutingOrder() { return routingOrder; }
    }

    /**
     * Creates and sends a DocuSign envelope
     */
    public String sendEnvelope(String issueKey, List<DocuSignDocument> documents, List<DocuSignSigner> signers) throws Exception {
        String accessToken = getAccessToken();
        
        // Build envelope JSON
        JsonObject envelope = new JsonObject();
        envelope.addProperty("emailSubject", "Documents to sign | Issue: " + issueKey);
        envelope.addProperty("status", "sent");
        
        // Add documents
        com.google.gson.JsonArray documentsArray = new com.google.gson.JsonArray();
        for (DocuSignDocument doc : documents) {
            JsonObject docJson = new JsonObject();
            docJson.addProperty("documentBase64", doc.getDocumentBase64());
            docJson.addProperty("name", doc.getName());
            docJson.addProperty("documentId", doc.getDocumentId());
            documentsArray.add(docJson);
        }
        envelope.add("documents", documentsArray);
        
        // Add recipients
        JsonObject recipients = new JsonObject();
        com.google.gson.JsonArray signersArray = new com.google.gson.JsonArray();
        for (DocuSignSigner signer : signers) {
            JsonObject signerJson = new JsonObject();
            signerJson.addProperty("email", signer.getEmail());
            signerJson.addProperty("name", signer.getName());
            signerJson.addProperty("recipientId", signer.getRecipientId());
            signerJson.addProperty("routingOrder", signer.getRoutingOrder());
            
            // Add signature tabs - required for DocuSign to know where to place signature fields
            JsonObject tabs = new JsonObject();
            com.google.gson.JsonArray signHereTabs = new com.google.gson.JsonArray();
            
            // Add a sign here tab for each document
            for (DocuSignDocument doc : documents) {
                JsonObject signHere = new JsonObject();
                signHere.addProperty("documentId", doc.getDocumentId());
                signHere.addProperty("pageNumber", "1"); // First page
                signHere.addProperty("xPosition", "100"); // X position in pixels
                signHere.addProperty("yPosition", "100"); // Y position in pixels
                signHereTabs.add(signHere);
            }
            
            tabs.add("signHereTabs", signHereTabs);
            signerJson.add("tabs", tabs);
            signersArray.add(signerJson);
        }
        recipients.add("signers", signersArray);
        envelope.add("recipients", recipients);
        
        // Send envelope creation request
        String envelopeUrl = basePath + "/v2.1/accounts/" + accountId + "/envelopes";
        HttpPost request = new HttpPost(envelopeUrl);
        request.setHeader("Authorization", "Bearer " + accessToken);
        request.setHeader("Content-Type", "application/json");
        
        StringEntity entity = new StringEntity(envelope.toString(), StandardCharsets.UTF_8);
        request.setEntity(entity);
        
        HttpResponse response = httpClient.execute(request);
        String responseBody = EntityUtils.toString(response.getEntity());
        
        if (response.getStatusLine().getStatusCode() != 201) {
            throw new RuntimeException("Failed to create envelope: " + responseBody);
        }
        
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        return jsonResponse.get("envelopeId").getAsString();
    }
}
