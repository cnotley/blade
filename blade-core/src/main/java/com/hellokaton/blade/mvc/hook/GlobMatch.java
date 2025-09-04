package com.hellokaton.blade.mvc.hook;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates extended glob patterns to regular expressions and tests them against an input path.
 * Supports *, **, ?, character ranges, and escapes. If pattern parsing fails the pattern will be
 * treated as a literal string and matched via {@link String#equalsIgnoreCase(String)}.
 */
@Slf4j
public class GlobMatch {

    private final Pattern pattern;
    private final String literal;

    private GlobMatch(Pattern pattern, String literal) {
        this.pattern = pattern;
        this.literal = literal;
    }

    public static GlobMatch compile(String glob, boolean caseInsensitive) {
        try {
            StringBuilder regex = new StringBuilder();
            boolean inClass = false;
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                switch (c) {
                    case '\\':
                        // escape next char if exists, otherwise escape literal backslash
                        if (i + 1 < glob.length()) {
                            char next = glob.charAt(++i);
                            regex.append(Pattern.quote(String.valueOf(next)));
                        } else {
                            regex.append("\\\\");
                        }
                        break;
                    case '[':
                        inClass = true;
                        regex.append('[');
                        // see if negated class
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '!') {
                            regex.append('^');
                            i++;
                        }
                        break;
                    case ']':
                        inClass = false;
                        regex.append(']');
                        break;
                    case '*':
                        if (!inClass) {
                            // collapse consecutive * and ** to .* allowing / if specification requires
                            if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                                regex.append(".*");
                                i++;
                            } else {
                                regex.append(".*");
                            }
                        } else {
                            regex.append('*');
                        }
                        break;
                    case '?':
                        if (!inClass) {
                            regex.append("[^/]");
                        } else {
                            regex.append('?');
                        }
                        break;
                    default:
                        regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            Pattern p = Pattern.compile("^" + regex.toString() + "$", flags);
            return new GlobMatch(p, null);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: Failed to compile glob pattern '{}', falling back to literal", glob, e);
            return new GlobMatch(null, glob);
        }
    }

    public boolean matches(String path) {
        if (pattern != null) {
            Matcher matcher = pattern.matcher(path);
            return matcher.find();
        }
        return literal.equalsIgnoreCase(path);
    }
}
