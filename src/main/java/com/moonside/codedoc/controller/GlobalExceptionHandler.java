package com.moonside.codedoc.controller;

import com.moonside.codedoc.exceptions.IngestionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Transforme les exceptions en réponses HTTP propres, pour TOUS les contrôleurs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Zip invalide, trop gros, chemin piégé... : erreur du client, donc 400. */
    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<ErrorResponse> handleIngestionError(IngestionException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getMessage()));
    }

    /** Fichier plus gros que la limite multipart du application.yml : 413. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("Le fichier envoyé est trop volumineux."));
    }

    public record ErrorResponse(String message) {
    }
}