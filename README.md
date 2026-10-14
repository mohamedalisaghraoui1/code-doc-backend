# Code-doc : backend

Spring Boot service that turns a zip archive of source code into a PDF documentation.
Upload a project, a local LLM (via [Ollama](https://ollama.com)) describes each class and method,
and the resulting PDF is stored in MongoDB so it can be downloaded later.

A web UI is available in the companion repository: [code-doc-frontend](https://github.com/YOUR-USERNAME/code-doc-frontend).

## How it works

1. A `.zip` archive is uploaded to the API.
2. The archive is extracted safely (size, entry-count and path-length limits).
3. Source files are detected by extension and parsed into classes and methods.
4. Each element is documented by a local LLM through Ollama.
   Results are cached by content hash, so unchanged code is not sent to the model again.
5. A PDF is generated with Apache PDFBox and saved in MongoDB.
6. The API returns the document metadata and a download link.

## Supported languages

Java, Python, JavaScript, TypeScript, C#, Go, Rust, PHP, Kotlin, Swift, Scala, C, C++, Ruby
(classes and functions are extracted), plus SQL, Shell, PowerShell, HTML, CSS and Vue
(documented at file level).

To add a language, declare it in `Language.java`, and add its parsing rules in `GenericSourceParser`
if you want its functions and classes extracted.

## Tech stack

- Java 21, Spring Boot 4.1
- MongoDB (Spring Data MongoDB)
- Ollama (local LLM, default model `qwen2.5-coder:1.5b`)
- JavaParser, Apache PDFBox 3
- Spring Boot Actuator

## Prerequisites

- JDK 21
- MongoDB running on `localhost:27017`
- [Ollama](https://ollama.com) installed and running, with a model pulled:

```bash
ollama pull qwen2.5-coder:1.5b
```

## Configuration

Settings live in `src/main/resources/application.properties`:

| Property                 | Default                            | Description                              |
|--------------------------|------------------------------------|------------------------------------------|
| `spring.mongodb.uri`     | `mongodb://localhost:27017/doc-gen`| MongoDB connection string               |
| `ollama.base-url`        | `http://localhost:11434`           | Ollama server URL                        |
| `ollama.model`           | `qwen2.5-coder:1.5b`               | Model used to write the documentation    |
| `ollama.timeout-seconds` | `120`                              | Timeout for a request to Ollama          |

Any property can be overridden with an environment variable, for example `SPRING_MONGODB_URI`
or `OLLAMA_MODEL`. Do not commit credentials: keep them in environment variables.

## Run

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`.

## API

| Method | Endpoint                      | Description                                             |
|--------|-------------------------------|---------------------------------------------------------|
| POST   | `/api/projects/upload`        | Upload a zip (`multipart/form-data`, field `file`)      |
| GET    | `/api/projects`               | List generated documents                                |
| GET    | `/api/projects/{id}/download` | Download the PDF stored in MongoDB                      |

`POST /api/projects/upload` is **synchronous**: the response is returned once the PDF is generated
and saved, which can take several minutes for large projects.

Example:

```bash
curl -X POST http://localhost:8080/api/projects/upload \
  -F "file=@my-project.zip"
```

Response:

```json
{
  "id": "…",
  "title": "my-project",
  "language": "Java, Python",
  "projectName": "my-project",
  "downloadUrl": "/api/projects/…/download",
  "methodsDocumented": 42,
  "methodsFromTrivial": 0,
  "methodsFromCache": 10,
  "methodsFailed": 0,
  "generatedAt": "2026-01-01T12:00:00Z"
}
```

Errors are returned as `{ "message": "…" }` with an appropriate HTTP status.

## Archive limits

To protect against zip bombs and oversized inputs:

- 200 MB maximum once decompressed
- 5 MB maximum per file
- 20,000 entries maximum
- 300 characters maximum per entry path

## Project structure

```
src/main/java/com/moonside/codedoc/
├── controller/    REST endpoints and global error handler
├── services/      zip extraction, scanning, parsing, LLM prompts, PDF generation, cache hashing
├── model/         documents, responses, cache entries, language and entity enums
├── repository/    MongoDB repositories
└── exceptions/    ingestion, parsing and generation errors
```
