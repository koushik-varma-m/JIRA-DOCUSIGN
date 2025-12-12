# DocuSign SDK Setup Instructions

The DocuSign Java SDK is not available in public Maven repositories. You need to install it manually.

## Option 1: Download and Install to Local Maven Repository (Recommended)

1. **Download the SDK JAR:**
   - Visit: https://github.com/docusign/docusign-esign-java-client/releases
   - Download the latest release JAR file (e.g., `docusign-esign-java-client-3.32.0.jar`)

2. **Install to Local Maven Repository:**
   ```bash
   mvn install:install-file \
     -Dfile=/path/to/docusign-esign-java-client-3.32.0.jar \
     -DgroupId=com.docusign \
     -DartifactId=docusign-esign-java \
     -Dversion=3.32.0 \
     -Dpackaging=jar
   ```

3. **Update pom.xml version** to match the version you downloaded

## Option 2: Use System Scope (Alternative)

1. Create a `lib` folder in your project root
2. Download the JAR and place it in `lib/`
3. Update pom.xml to use system scope (see below)

If using system scope, update the dependency in pom.xml:
```xml
<dependency>
    <groupId>com.docusign</groupId>
    <artifactId>docusign-esign-java</artifactId>
    <version>3.32.0</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/docusign-esign-java-client-3.32.0.jar</systemPath>
</dependency>
```

## After Installation

Run `atlas-clean && atlas-run` to rebuild and test.


