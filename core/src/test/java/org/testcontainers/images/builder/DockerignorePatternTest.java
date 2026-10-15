package org.testcontainers.images.builder;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerignorePatternTest {

    private static final String MOBY_CASES = "/dockerignore/moby-patternmatcher-cases.tsv";

    static Stream<Arguments> mobyCases() throws IOException {
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    DockerignorePatternTest.class.getResourceAsStream(MOBY_CASES),
                    StandardCharsets.UTF_8
                )
            )
        ) {
            return reader
                .lines()
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.split("\t", -1))
                .map(columns -> Arguments.of(columns[0], columns[1], columns[2]))
                .collect(Collectors.toList())
                .stream();
        }
    }

    @ParameterizedTest(name = "[{index}] {0} matches {1}: {2}")
    @MethodSource("mobyCases")
    void matchesLikeMoby(String pattern, String path, String expected) {
        if ("invalid".equals(expected)) {
            assertThatThrownBy(() -> DockerignorePattern.compile(pattern))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(pattern);
        } else {
            assertThat(DockerignorePattern.compile(pattern).matches(path))
                .as("pattern '%s' against path '%s'", pattern, path)
                .isEqualTo(Boolean.parseBoolean(expected));
        }
    }

    static Stream<Arguments> deliberateDifferences() {
        return Stream.of(
            // moby turns * and ? inside a class into a broken regular expression: [*] matches "]" but not "*"
            Arguments.of("[*]", "*", true),
            Arguments.of("[*]", "]", false),
            Arguments.of("[?a]", "?", true)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} matches {1}: {2}")
    @MethodSource("deliberateDifferences")
    void treatsWildcardsInsideClassesAsLiterals(String pattern, String path, boolean expected) {
        assertThat(DockerignorePattern.compile(pattern).matches(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("unsupportedEscapes")
    void rejectsEscapedLettersThatMobyTreatsAsControlCharacters(String pattern) {
        // moby passes these to Go's regexp package, where \t is a tab and \n a newline
        assertThatThrownBy(() -> DockerignorePattern.compile(pattern))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported escape sequence");
    }

    static Stream<String> unsupportedEscapes() {
        return Stream.of("a\\t", "a\\n", "[\\t]", "[\\b]");
    }
}
