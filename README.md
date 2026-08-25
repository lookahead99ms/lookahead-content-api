# Look Ahead Content API

A production-oriented Spring Boot API for learning paths, courses, questions, and content delivery in the Look Ahead Learning Platform. The current foundation is intentionally database-free and will evolve toward S3-backed public content and authorized premium-content delivery.

## Technology

- Java 21
- Spring Boot 4.1.1
- Maven Wrapper
- Spring Web MVC and Bean Validation
- Spring Boot Actuator
- Springdoc OpenAPI and Swagger UI
- JUnit integration tests
- Docker
- GitHub Actions

## Requirements

- Java 21+
- Docker (optional)

## Run locally

```shell
./mvnw spring-boot:run
```

Useful URLs:

- API status: http://localhost:8080/api/v1/status
- Health: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html

## Test and package

```shell
./mvnw clean verify
```

The packaged application is `target/lookahead-content-api.jar`.

## API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/status` | Application status and version |
| `GET` | `/actuator/health` | Operational health |
| `GET` | `/v3/api-docs` | OpenAPI JSON |
| `GET` | `/swagger-ui.html` | Interactive API documentation |

Example response:

```json
{
  "data": {
    "application": "lookahead-content-api",
    "status": "UP",
    "version": "0.0.1-SNAPSHOT"
  },
  "timestamp": "2026-08-25T12:00:00Z"
}
```

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` | Selects `local`, `test`, or `prod` configuration |
| `FRONTEND_ORIGIN` | `http://localhost:4200` | Allowed Angular origin for API CORS |

No secrets are required in Phase 1.

## Docker

Build the application before building the image:

```shell
./mvnw clean package
docker build -t lookahead-content-api:local .
docker run --rm -p 8080:8080 lookahead-content-api:local
```

## Architecture and roadmap

See [docs/architecture.md](docs/architecture.md) for the service boundary and planned S3, CloudFront, authorization, and AWS evolution.

## Continuous integration

Every push and pull request to `main` runs the Maven test suite, creates the executable JAR, and validates the Docker image build.
