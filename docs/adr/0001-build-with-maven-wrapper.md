# Build with Maven through the wrapper

## Context

No build tool is installed on the machine or assumed on a CI runner. The project leans on
the Java standard library, but it still needs dependency resolution (JUnit for the tests
now, a JDBC driver later), a test runner wired to a classpath, and a build a stranger with
only a JDK can run. That build has to be pinned so a checkout a year from now works the
same way.

## Decision

Maven 3.9.16, run through the checked-in Maven Wrapper 3.3.4 with `distributionType`
`only-script`. The two wrapper scripts come from the official distribution zip on Maven
Central, not from the source tree, whose scripts still carry unsubstituted build tokens.
The Maven distribution is pinned by SHA-256 in `.mvn/wrapper/maven-wrapper.properties`, so
the first build fetches exactly that Maven and verifies it before running.

## Alternatives

Gradle. Its wrapper commits a binary jar, its build is code in a Kotlin or Groovy DSL, and
it carries a daemon. That is more machinery than one module needs, and Maven is the more
common tool on the Java teams this project is aimed at.

Plain `javac` plus shell scripts. Hand-rolling dependency fetch, classpath assembly, and a
test loop is more custom tooling than Maven itself, and it steals effort from the domain,
which is where the effort belongs.

Maven 4. Still a release candidate at the time of writing, so not pinned for a build that
should be reproducible for years.

The wrapper's `jar` distribution type. It commits a binary blob to a repo built to be read,
for no gain, since `curl` and PowerShell are present everywhere this builds.

## Consequences

The first build needs the network once to fetch Maven, then works offline. The wrapper
scripts keep their Apache-2.0 headers inside an MIT repo, which is normal and expected. The
build is reproducible from a bare JDK 25 checkout. Upgrading Maven later is a two-line
change in `maven-wrapper.properties`.

## Status

Accepted.
