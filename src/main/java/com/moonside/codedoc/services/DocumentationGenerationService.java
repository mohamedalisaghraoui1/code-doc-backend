package com.moonside.codedoc.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moonside.codedoc.exceptions.GenerationException;
import com.moonside.codedoc.model.CodeEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Appelle un LLM local via Ollama pour transformer une méthode ou un type
 * (fait structuré) en explication en langage naturel. Gratuit et illimité :
 * le modèle tourne sur la machine, pas besoin de clé API ni de connexion
 * internet.
 *
 * Prérequis : Ollama installé et lancé (https://ollama.com), avec un modèle
 * téléchargé, ex. `ollama pull qwen2.5-coder:1.5b`.
 */
@Service
public class DocumentationGenerationService {

    private final RestClient restClient;
    private final PromptBuilder promptBuilder;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentationGenerationService(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:qwen2.5-coder:1.5b}") String model,
            @Value("${ollama.timeout-seconds:120}") long timeoutSeconds,
            PromptBuilder promptBuilder) {
        this.model = model;
        this.promptBuilder = promptBuilder;

        // Sans timeout explicite, un appel qui ne répond jamais (Ollama plantée,
        // modèle en train de charger...) bloquerait le thread indéfiniment.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("content-type", "application/json")
                .build();
    }

    /** Génère l'explication en langage naturel d'une seule méthode (ou d'un constructeur). */
    public String generateMethodDoc(CodeEntity method) {
        return callOllama(promptBuilder.buildMethodPrompt(method), method.id());
    }

    /** Génère la description d'une classe, interface, enum ou record (exceptions comprises). */
    public String generateTypeDoc(CodeEntity type, List<CodeEntity> members) {
        return callOllama(promptBuilder.buildTypePrompt(type, members), type.id());
    }

    private String callOllama(String prompt, String entityId) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false,
                // num_predict plafonne la longueur de la réponse : sans ça, le
                // modèle peut continuer bien au-delà des "2-4 phrases" demandées,
                // ce qui ralentit chaque appel pour rien.
                "options", Map.of("num_predict", 140),
                // Garde le modèle chargé en RAM entre deux appels : sans ça,
                // Ollama le décharge après quelques minutes d'inactivité, et le
                // rechargement ajoute plusieurs secondes de latence cachée sur
                // l'appel suivant.
                "keep_alive", "30m"
        );

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri("/api/generate")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new GenerationException(
                    "Échec de l'appel à Ollama (modèle " + model + "). "
                            + "Vérifie qu'il est lancé et que le modèle est téléchargé "
                            + "(`ollama pull " + model + "`).", e);
        }

        String text = extractText(rawResponse);
        if (text.isBlank()) {
            throw new GenerationException("Réponse vide d'Ollama pour " + entityId);
        }
        return text;
    }

    /** Ollama renvoie un JSON avec un champ "response" contenant le texte généré. */
    private String extractText(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            return root.path("response").asText("").trim();
        } catch (Exception e) {
            throw new GenerationException("Réponse d'Ollama illisible : " + rawResponse, e);
        }
    }
}