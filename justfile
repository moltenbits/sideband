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

# The rename is atomic, so a listener already blocked in the old binary keeps
# running on its own inode.
# Build the native executable and put it onto PATH
install: native
    mkdir -p {{install_dir}}
    cp {{native_binary}} {{install_dir}}/sideband.tmp
    mv -f {{install_dir}}/sideband.tmp {{install_dir}}/sideband
    @echo "Installed {{install_dir}}/sideband"

# Refuses to overwrite a real directory or a link pointing elsewhere.
# Link both client skills from their user skill roots into this checkout
install-skills:
    #!/usr/bin/env sh
    set -e
    link() {
        root="$1"; target="$2"; dest="$root/sideband"
        mkdir -p "$root"
        if [ -L "$dest" ]; then
            current="$(readlink "$dest")"
            if [ "$current" = "$target" ]; then echo "ok       $dest"; return; fi
            echo "conflict $dest -> $current (expected $target)"; return 1
        elif [ -e "$dest" ]; then
            echo "conflict $dest exists and is not a link"; return 1
        fi
        ln -s "$target" "$dest"; echo "linked   $dest -> $target"
    }
    link "$HOME/.claude/skills" "{{justfile_directory()}}/skills/sideband-claude"
    link "$HOME/.agents/skills" "{{justfile_directory()}}/skills/sideband-codex"

# Run tests and the native build, the pre-commit gate
check: test native

# Remove build output
clean:
    ./gradlew clean

# Show the resolved Java, Gradle, and native-image toolchain, then Sideband's own report
doctor:
    @java -version
    @./gradlew --version | grep -E '^(Gradle|Kotlin|JVM)'
    @which native-image && native-image --version
    @command -v sideband >/dev/null && sideband doctor --repo {{justfile_directory()}} || echo "sideband not installed"
