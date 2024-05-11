# Email Integrator

## Resources and URI Mappings

- Send email example - POST /email
- Get app health - GET /actuator/health

## Build Application

As part of the Maven build, automatically (Maven will invoke the whole build, hence each and every phase till the install, as such any execution listed in the POM with related bindings)


```bash
mvn clean install
```

As part of an invocation to the specific phase: Maven will invoke all the executions with a bind to it (and any previous phase), hence not only the specific execution you mentioned

```bash
mvn generate-sources
```

Make Maven to copy dependencies into target/lib supposing
- you don't want to alter the pom.xml
- you don't want test scoped (e.g. junit.jar) or provided dependencies (e.g. wlfullclient.jar)

```bash
mvn install dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
```

## Run Application

```bash
mvn spring-boot:run
```

## API Services
- [Brevo](https://github.com/sendinblue/APIv3-java-library?tab=readme-ov-file)

### Vault

Serve vault locally @ http://127.0.0.1:8200/ui/vault/auth?with=token

```bash
vault server -dev
```

## Tools

- Java 17
- Maven
- Spring Boot Starter