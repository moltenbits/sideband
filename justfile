# Common development tasks for Sideband. Run `just` to list them.

native_binary := "build/native/nativeCompile/sideband"
install_dir := env_var_or_default("SIDEBAND_INSTALL_DIR", env_var("HOME") + "/.local/bin")

# List available tasks
default:
    @just --list --unsorted

# Compile main and test sources without running tests
build:
    ./gradlew classes testClasses

# Run the Spock test suite
test *args:
    ./gradlew test {{args}}

# Run a single specification by simple class name, e.g. `just test-one SidebandCommandSpec`
test-one spec:
    ./gradlew test --tests '*{{spec}}'

# Build the GraalVM native executable
native:
    ./gradlew nativeCompile

# Build the native executable and run it with the given arguments
native-run *args: native
    ./{{native_binary}} {{args}}

# Run the CLI on the JVM with the given arguments (fast iteration, no native build)
run *args:
    ./gradlew run --quiet --args='{{args}}'

# Build the native executable and copy it onto PATH
install: native
    mkdir -p {{install_dir}}
    cp {{native_binary}} {{install_dir}}/sideband
    @echo "Installed {{install_dir}}/sideband"

# Run tests and the native build, the pre-commit gate
check: test native

# Remove build output
clean:
    ./gradlew clean

# Show the resolved Java, Gradle, and native-image toolchain
doctor:
    @java -version
    @./gradlew --version | grep -E '^(Gradle|Kotlin|JVM)'
    @which native-image && native-image --version
