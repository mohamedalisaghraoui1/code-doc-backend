package com.moonside.codedoc.services;

import com.moonside.codedoc.exceptions.GenerationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Empreinte SHA-256 du code d'une méthode : sert de clé au cache de docs. */
@Component
public class DocHasher {

    public String hash(String signature, String sourceCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((signature + "\n" + sourceCode).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new GenerationException("SHA-256 indisponible.", e);
        }
    }
}