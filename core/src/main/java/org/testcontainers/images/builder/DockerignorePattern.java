package org.testcontainers.images.builder;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A single {@code .dockerignore} pattern, matched the way the Docker CLI and BuildKit match it
 * ({@code github.com/moby/patternmatcher} v0.6.1).
 * <p>
 * The pattern must already be cleaned (no {@code ./}, {@code //}, {@code ..} or trailing {@code /}) and must not carry
 * the {@code !} exclusion prefix. Paths are slash-separated and relative to the build context root.
 * <p>
 * Pattern syntax is Go's {@code filepath.Match} syntax plus {@code **}, which matches any number of directories
 * (including none). Two deliberate differences from moby, where its behavior is accidental:
 * <ul>
 *     <li>{@code *} and {@code ?} inside a character class are literal characters (moby produces a broken class)</li>
 *     <li>an escaped letter or digit is only accepted for {@code \d \D \s \S \w \W}, and outside a class also
 *     {@code \b \B \A \z}, which moby passes through as regular expression escapes; other escaped letters and digits
 *     are rejected</li>
 * </ul>
 */
final class DockerignorePattern {

    private static final String REGEX_ESCAPES = "dDsSwWbBAz";

    private static final String CLASS_REGEX_ESCAPES = "dDsSwW";

    private static final String META_CHARACTERS = "*?[]\\";

    private final String pattern;

    private final Predicate<String> matcher;

    private DockerignorePattern(String pattern, Predicate<String> matcher) {
        this.pattern = pattern;
        this.matcher = matcher;
    }

    /**
     * @param pattern a cleaned pattern, without the {@code !} prefix
     * @throws IllegalArgumentException if the pattern is malformed
     */
    static DockerignorePattern compile(String pattern) {
        validate(pattern);
        return new DockerignorePattern(pattern, buildMatcher(pattern));
    }

    /**
     * @return true if the pattern matches the path itself or one of its parent directories
     */
    boolean matches(String path) {
        if (matcher.test(path)) {
            return true;
        }
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            if (matcher.test(path.substring(0, slash))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return pattern;
    }

    private static Predicate<String> buildMatcher(String pattern) {
        if (!containsMeta(pattern)) {
            return pattern::equals;
        }
        if (pattern.startsWith("**")) {
            String rest = pattern.substring(2);
            boolean directoryBoundary = rest.startsWith("/");
            if (directoryBoundary) {
                rest = rest.substring(1);
            }
            if (!containsMeta(rest)) {
                // moby matches a literal tail after a leading ** as a plain suffix, so "**file" also matches "myfile"
                String suffix = rest;
                return directoryBoundary
                    ? path -> path.equals(suffix) || path.endsWith("/" + suffix)
                    : path -> path.endsWith(suffix);
            }
        }
        Pattern regex = toRegex(pattern);
        return path -> regex.matcher(path).matches();
    }

    private static boolean containsMeta(String pattern) {
        return pattern.chars().anyMatch(c -> META_CHARACTERS.indexOf(c) >= 0);
    }

    private static Pattern toRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            int c = pattern.codePointAt(i);
            i += Character.charCount(c);
            switch (c) {
                case '*':
                    if (i < pattern.length() && pattern.charAt(i) == '*') {
                        i++;
                        if (i < pattern.length() && pattern.charAt(i) == '/') {
                            i++;
                        }
                        regex.append(i == pattern.length() ? ".*" : "(?:.*/)?");
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '\\':
                    int escaped = pattern.codePointAt(i);
                    i += Character.charCount(escaped);
                    appendEscape(regex, pattern, escaped, REGEX_ESCAPES);
                    break;
                case '[':
                    i = appendClass(regex, pattern, i);
                    break;
                case '^':
                    // moby leaves ^ unescaped, so it acts as an anchor and can only match at the start
                    regex.append('^');
                    break;
                default:
                    appendLiteral(regex, c);
            }
        }
        try {
            return Pattern.compile(regex.toString());
        } catch (PatternSyntaxException e) {
            throw invalid(pattern, e.getDescription());
        }
    }

    /**
     * Appends the character class starting just after its {@code [}, and returns the index after its {@code ]}.
     */
    private static int appendClass(StringBuilder regex, String pattern, int i) {
        regex.append('[');
        if (pattern.charAt(i) == '^') {
            regex.append('^');
            i++;
        }
        // validate() has checked the class is non-empty and terminated, so the first unescaped ']' closes it
        while (pattern.charAt(i) != ']') {
            int c = pattern.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\\') {
                int escaped = pattern.codePointAt(i);
                i += Character.charCount(escaped);
                appendEscape(regex, pattern, escaped, CLASS_REGEX_ESCAPES);
            } else if (c == '-') {
                regex.append('-');
            } else {
                appendLiteral(regex, c);
            }
        }
        regex.append(']');
        return i + 1;
    }

    private static void appendEscape(StringBuilder regex, String pattern, int escaped, String allowedRegexEscapes) {
        if (escaped < 128 && Character.isLetterOrDigit(escaped)) {
            if (allowedRegexEscapes.indexOf(escaped) < 0) {
                throw invalid(pattern, "unsupported escape sequence \\" + (char) escaped);
            }
            regex.append('\\').append((char) escaped);
        } else {
            appendLiteral(regex, escaped);
        }
    }

    private static void appendLiteral(StringBuilder regex, int c) {
        if (c < 128 && Character.isLetterOrDigit(c)) {
            regex.append((char) c);
        } else {
            regex.append("\\x{").append(Integer.toHexString(c)).append('}');
        }
    }

    /**
     * Checks the pattern against Go's {@code filepath.Match} syntax, as moby does before using a pattern.
     */
    private static void validate(String pattern) {
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 >= pattern.length()) {
                    throw invalid(pattern, "trailing backslash");
                }
                i += 1 + Character.charCount(pattern.codePointAt(i + 1));
            } else if (c == '[') {
                i = validateClass(pattern, i + 1);
            } else {
                i++;
            }
        }
    }

    private static int validateClass(String pattern, int i) {
        if (i < pattern.length() && pattern.charAt(i) == '^') {
            i++;
        }
        boolean empty = true;
        while (true) {
            if (i >= pattern.length()) {
                throw invalid(pattern, "unterminated character class");
            }
            if (pattern.charAt(i) == ']' && !empty) {
                return i + 1;
            }
            i = validateClassCharacter(pattern, i);
            if (i < pattern.length() && pattern.charAt(i) == '-') {
                i = validateClassCharacter(pattern, i + 1);
            }
            empty = false;
        }
    }

    private static int validateClassCharacter(String pattern, int i) {
        if (i >= pattern.length()) {
            throw invalid(pattern, "unterminated character class");
        }
        char c = pattern.charAt(i);
        if (c == '-' || c == ']') {
            throw invalid(pattern, "unexpected '" + c + "' in character class");
        }
        if (c == '\\') {
            i++;
            if (i >= pattern.length()) {
                throw invalid(pattern, "unterminated character class");
            }
        }
        return i + Character.charCount(pattern.codePointAt(i));
    }

    private static IllegalArgumentException invalid(String pattern, String reason) {
        return new IllegalArgumentException("Invalid .dockerignore pattern '" + pattern + "': " + reason);
    }
}
