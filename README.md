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

### Docker

Build Docker Image

```bash
 docker build -t hoseacodes-emailintegrator .    
 ```

Run Docker Image

```bash
docker run -p 8080:8080 hoseacodes-emailintegrator
```

Tage Docker Image for Push

```bash
docker tag ${imageID} hoseacodes/hoseacodes-emailintegrator:latest
```

Push Docker Image 

```bash
docker push hoseacodes/hoseacodes-emailintegrator:latest        
```

## Tools

- Java 17
- Maven
- Spring Boot Starter

## Future Enhancements 

- [ ] [Deploy to Azure](https://spring.io/guides/gs/spring-boot-for-azure)
- [ ] [Implement Basic Auth](https://medium.com/javarevisited/spring-boot-securing-api-with-basic-authentication-bdd3ad2266f5)
  - [Another Basic Auth Method](https://www.geeksforgeeks.org/spring-security-basic-authentication/)
- [ ] [Basic Auth RESTTemplate](https://www.baeldung.com/how-to-use-resttemplate-with-basic-authentication-in-spring)
- [ ] [Add Swagger]()
- [ ] [Add Junit Tests]()
- [ ] [Add PIT Tests]()
- [ ] [Add Karate Tests]()
- [ ] [Add Test Thresholds]()
- [ ] [Create & Deploy Evidence of Tests]()
- [ ] [Add Github Actions]()
  - [ ] Build Job
  - [ ] Deploy Job
  - [ ] Add Snyk scans
  - [ ] Add linter job
  - [ ] Test job
  - [ ] Secret scan 