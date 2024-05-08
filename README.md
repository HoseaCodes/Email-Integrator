# Email Integrator

## Resources and URI Mappings

- Get email example - GET /email
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

## Run Application

```bash
mvn spring-boot:run
```


## Tools

- Java 17
- Maven
- Spring Boot Starter