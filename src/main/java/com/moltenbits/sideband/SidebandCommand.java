package com.moltenbits.sideband;

import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "sideband", description = "Local, durable, tridirectional communication between a human, Claude Code, and Codex",
        mixinStandardHelpOptions = true)
public class SidebandCommand implements Runnable {

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    boolean verbose;

    public static void main(String[] args) throws Exception {
        PicocliRunner.run(SidebandCommand.class, args);
    }

    public void run() {
        // business logic here
        if (verbose) {
            System.out.println("Hi!");
        }
    }
}
