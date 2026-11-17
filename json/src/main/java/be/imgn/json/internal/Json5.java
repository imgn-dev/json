package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigInteger;
import java.util.Arrays;

/// Parses [JSON5 1.0.0](https://spec.json5.org): translates it to JSON in one pass, then builds the tape of the
/// translation, whose offsets a [Locator] maps back to the source.
///
/// The translation checks the tokens, which JSON5 takes from ECMAScript 5.1: identifiers, strings, numbers, comments
/// and white space. It leaves the structure (brackets, colons, commas between values, duplicate names, nesting) to the
/// tape builder, which sees it unchanged. What it does:
///
/// - comments and white space are dropped, a space separating two values or names that would otherwise touch;
/// - a trailing comma is dropped; any other comma is kept, for the tape builder to judge;
/// - a name written as an identifier is quoted: an identifier is a name where an object expects one, and `true`,
///   `false`, `null`, `Infinity` or `NaN` where a value is expected;
/// - a string is written between double quotes, with JSON's escape sequences;
/// - a number is written as JSON writes it: `+1`, `.5`, `5.` and `0x1F` become `1`, `0.5`, `5.0` and `31`; `NaN`,
///   `Infinity` and `-Infinity` stay, for the tape builder to flag.
public final class Json5 {

    private static final int HEX_RADIX = 16;
    private static final int HEX_BITS = 4;
    private static final int HEX_MASK = 0xF;
    private static final int UNICODE_HEX_DIGITS = 4;
    private static final int CRLF_CONTINUATION = 3;
    private static final int UNICODE_ESCAPE_LENGTH = 6;
    private static final int HEX_ESCAPE_LENGTH = 4;
    private static final int LINE_SEPARATOR = 0x2028;
    private static final int PARAGRAPH_SEPARATOR = 0x2029;
    private static final int NO_BREAK_SPACE = 0xA0;
    private static final int BYTE_ORDER_MARK = 0xFEFF;
    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VERTICAL_TAB = 0x0B;
    private static final int FORM_FEED = 0x0C;
    private static final int INITIAL_SHIFTS = 16;
    private static final byte[] INFINITY = "Infinity".getBytes(ISO_8859_1);
    private static final byte[] NAN = "NaN".getBytes(ISO_8859_1);
    private static final byte[] HEX_DIGITS = "0123456789ABCDEF".getBytes(ISO_8859_1);

    private final byte[] source;
    private final int end;
    private final Limits limits;

    private byte[] json;
    private int size;
    private int p;

    // Where the translation shifts offsets: see Locator.
    private int[] translated = new int[INITIAL_SHIFTS];
    private int[] original = new int[INITIAL_SHIFTS];
    private int shifts;

    // The open containers, true for an object, and what came last: one of { [ , : or V for a value or a name.
    private boolean[] objects = new boolean[INITIAL_SHIFTS];
    private int depth;
    private int last = '[';
    // The source offset of a comma not yet written, as it is dropped if it trails; -1 when none.
    private int comma = -1;
    // The length of the translation before the token being translated.
    private int tokenStart;

    private Json5(byte[] source, Limits limits) {
        this.source = source;
        this.end = source.length;
        this.limits = limits;
        this.json = new byte[source.length + INITIAL_SHIFTS];
    }

    /// Validates a JSON5 document and builds the tape of its translation to JSON.
    ///
    /// @param source the document, in UTF-8
    /// @throws TapeException if the document is not valid JSON5, or exceeds a limit
    public static Tape build(byte[] source, Limits limits) {
        if (source.length > limits.maxDocumentLength()) {
            throw new TapeException(
                    "the document is longer than the maximum of " + limits.maxDocumentLength() + " bytes",
                    limits.maxDocumentLength(),
                    "",
                    Locator.of(source));
        }
        var json5 = new Json5(source, limits);
        json5.translate();
        // The length of the source is checked: the translation may be a little longer.
        var translatedLimits =
                new Limits(limits.maxDepth(), Integer.MAX_VALUE, limits.maxStringLength(), limits.maxNumberLength());
        return TapeBuilder.build(json5.json, 0, json5.size, translatedLimits, Syntax.JSON5, json5.locator());
    }

    private Locator locator() {
        return Locator.translated(source, translated, original, shifts);
    }

    // The tokens.

    private void translate() {
        while (true) {
            // An error in the comments before a token comes after everything written so far.
            tokenStart = size;
            if (!skipSpace()) {
                break;
            }
            var b = source[p];
            switch (b) {
                case '{', '[' -> {
                    open(b == '{');
                    punctuator(b);
                }
                case '}', ']' -> {
                    if (comma >= 0 && last != '{' && last != '[' && last != ',') {
                        // A trailing comma.
                        comma = -1;
                    }
                    depth = Math.max(0, depth - 1);
                    punctuator(b);
                }
                case ',' -> {
                    flushComma();
                    comma = p;
                    p++;
                }
                case ':' -> punctuator(b);
                case '"', '\'' -> {
                    startToken();
                    string(b);
                    last = 'V';
                }
                default -> {
                    startToken();
                    other();
                }
            }
        }
        flushComma();
    }

    private void open(boolean object) {
        depth++;
        if (depth == objects.length) {
            objects = Arrays.copyOf(objects, depth * 2);
        }
        objects[depth] = object;
    }

    private void punctuator(byte b) {
        flushComma();
        shift();
        emit(b);
        last = b;
        p++;
    }

    /// Writes the comma not yet written, before a token that shows it does not trail.
    private void flushComma() {
        if (comma >= 0) {
            shift(size, comma);
            emit((byte) ',');
            last = ',';
            comma = -1;
        }
    }

    /// Starts a value or a name: after the comma before it, and after a space if the previous one ends just before.
    private void startToken() {
        flushComma();
        if (last == 'V') {
            emit((byte) ' ');
        }
        tokenStart = size;
        shift();
    }

    private boolean expectsName() {
        return depth > 0 && objects[depth] && (last == '{' || last == ',');
    }

    /// Translates a token that is neither a punctuator nor a string: a number, an identifier, or a byte the tape
    /// builder rejects.
    private void other() {
        var b = source[p];
        var start = p;
        if (b == '+' || b == '-' || b == '.' || (b >= '0' && b <= '9')) {
            number();
        } else if (identifierStart(p) >= 0) {
            var name = expectsName();
            identifier(name);
            if (!name) {
                literal(start);
            }
        } else {
            // Not JSON5: written as it is, for the tape builder to report it as it reports any byte it does not expect.
            var after = b >= 0 ? p + 1 : Utf8.sequenceEnd(source, p, end);
            if (after < 0) {
                throw error(p, TapeBuilder.invalidUtf8(source, p, 1));
            }
            emit(source, p, after - p);
            p = after;
        }
        last = 'V';
    }

    // White space and comments.

    /// Skips white space and comments, and returns whether a token follows.
    private boolean skipSpace() {
        while (p < end) {
            var b = source[p];
            if (isAsciiSpace(b)) {
                p++;
            } else if (b == '/' && p + 1 < end && (source[p + 1] == '/' || source[p + 1] == '*')) {
                comment();
            } else if (b < 0 && isSpace(codePoint(p))) {
                p = Utf8.sequenceEnd(source, p, end);
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == VERTICAL_TAB || b == FORM_FEED;
    }

    private static boolean isSpace(int codePoint) {
        return codePoint == NO_BREAK_SPACE
                || codePoint == BYTE_ORDER_MARK
                || codePoint == LINE_SEPARATOR
                || codePoint == PARAGRAPH_SEPARATOR
                || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
    }

    private void comment() {
        if (source[p + 1] == '/') {
            lineComment();
        } else {
            blockComment();
        }
    }

    private void lineComment() {
        p += 2;
        while (p < end && !isLineTerminator(p)) {
            p = commentCharacter();
        }
    }

    private void blockComment() {
        p += 2;
        while (p + 1 < end && !(source[p] == '*' && source[p + 1] == '/')) {
            p = commentCharacter();
        }
        if (p + 1 >= end) {
            throw error(end, "expected '*/' to end the comment, found the end of the document");
        }
        p += 2;
    }

    /// Returns the offset after the character of a comment at [#p], checking it is valid UTF-8.
    private int commentCharacter() {
        if (source[p] >= 0) {
            return p + 1;
        }
        codePoint(p);
        return Utf8.sequenceEnd(source, p, end);
    }

    /// Returns whether a line terminator of ECMAScript is at an offset: a line feed, a carriage return, U+2028 or
    /// U+2029.
    private boolean isLineTerminator(int at) {
        var b = source[at];
        if (b == '\n' || b == '\r') {
            return true;
        }
        if (b >= 0) {
            return false;
        }
        var codePoint = codePoint(at);
        return codePoint == LINE_SEPARATOR || codePoint == PARAGRAPH_SEPARATOR;
    }

    // Strings.

    private void string(byte quote) {
        emit((byte) '"');
        p++;
        while (true) {
            var run = p;
            p = Swar.indexOfStringSpecial(source, p, end, quote);
            if (p > run) {
                shift(size, run);
                emit(source, run, p - run);
            }
            if (p >= end) {
                throw error(end, "expected " + quoteName(quote) + " to end the string, found the end of the document");
            }
            var b = source[p];
            if (b == quote) {
                emit((byte) '"');
                p++;
                return;
            }
            if (b == '\\') {
                escape();
            } else if (b == '"') {
                shift(size, p);
                emit((byte) '\\');
                emit((byte) '"');
                p++;
            } else if (b == '\n' || b == '\r') {
                throw error(p, "unescaped line terminator U+%04X in a string".formatted((int) b));
            } else {
                // Another control character: JSON5 has it as it is, JSON escaped.
                shift(size, p);
                escapeControl(b);
                p++;
            }
        }
    }

    private static String quoteName(byte quote) {
        return quote == '"' ? "'\"'" : "\"'\"";
    }

    private void escapeControl(int c) {
        switch (c) {
            case '\b' -> emitAscii("\\b");
            case '\f' -> emitAscii("\\f");
            case '\t' -> emitAscii("\\t");
            default -> unicodeEscape(c);
        }
    }

    private void unicodeEscape(int unit) {
        emitAscii("\\u");
        for (var bits = HEX_BITS * (UNICODE_HEX_DIGITS - 1); bits >= 0; bits -= HEX_BITS) {
            emit(HEX_DIGITS[(unit >> bits) & HEX_MASK]);
        }
    }

    /// Translates the escape sequence at [#p].
    private void escape() {
        var backslash = p;
        if (p + 1 >= end) {
            throw error(end, "expected a character after '\\', found the end of the document");
        }
        var c = source[p + 1];
        shift(size, backslash);
        switch (c) {
            case '\'' -> {
                emit((byte) '\'');
                p += 2;
            }
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                emit(source, p, 2);
                p += 2;
            }
            case 'v' -> {
                unicodeEscape(VERTICAL_TAB);
                p += 2;
            }
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'x', 'u' -> codeEscape(c);
            case '\n' -> p += 2;
            case '\r' -> p += p + 2 < end && source[p + 2] == '\n' ? CRLF_CONTINUATION : 2;
            default -> nonEscapeCharacter();
        }
    }

    /// Translates an escape sequence that gives a code: a backslash followed by `0`, by `x` and 2 hexadecimal digits,
    /// or by `u` and 4; a backslash followed by another digit is an error.
    private void codeEscape(byte c) {
        switch (c) {
            case '0' -> {
                if (p + 2 < end && isDigit(source[p + 2])) {
                    throw error(p + 2, "a digit cannot follow \\0 in a string");
                }
                unicodeEscape(0);
                p += 2;
            }
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                throw error(p + 1, "expected an escape sequence after '\\', found " + describe(p + 1));
            case 'x' -> {
                unicodeEscape(hex(p + 2, 2, "\\x"));
                p += HEX_ESCAPE_LENGTH;
            }
            default -> {
                // A backslash followed by u.
                hex(p + 2, UNICODE_HEX_DIGITS, "\\u");
                emit(source, p, UNICODE_ESCAPE_LENGTH);
                p += UNICODE_ESCAPE_LENGTH;
            }
        }
    }

    /// Translates a backslash followed by a character with no escape of its own, which stands for that character, or
    /// by U+2028 or U+2029, which continue the string on the next line.
    private void nonEscapeCharacter() {
        var c = source[p + 1];
        if (c >= 0) {
            // A control character, which JSON escapes, or a printable one, which needs no escape in JSON.
            if (c < ' ') {
                escapeControl(c);
            } else {
                emit(c);
            }
            p += 2;
            return;
        }
        var codePoint = codePoint(p + 1);
        var after = Utf8.sequenceEnd(source, p + 1, end);
        if (codePoint != LINE_SEPARATOR && codePoint != PARAGRAPH_SEPARATOR) {
            emit(source, p + 1, after - p - 1);
        }
        p = after;
    }

    /// Returns the value of hexadecimal digits at an offset.
    private int hex(int from, int count, String escape) {
        var value = 0;
        for (var i = from; i < from + count; i++) {
            var digit = i < end ? Character.digit(source[i], HEX_RADIX) : -1;
            if (digit < 0) {
                throw error(
                        i, "expected a hexadecimal digit in a " + escape + " escape sequence, found " + describe(i));
            }
            value = value * HEX_RADIX + digit;
        }
        return value;
    }

    // Numbers.

    private void number() {
        var start = p;
        var sign = source[p];
        if (sign == '+' || sign == '-') {
            p++;
        }
        if (startsWith(p, INFINITY) || startsWith(p, NAN)) {
            nonFinite(sign);
        } else if (p + 1 < end && source[p] == '0' && (source[p + 1] == 'x' || source[p + 1] == 'X')) {
            hexNumber(start, sign == '-');
        } else {
            decimal(start, sign);
        }
        if (p < end && (isDigit(source[p]) || identifierStart(p) >= 0)) {
            throw error(p, "expected the end of the number, found " + describe(p));
        }
        checkLength(start);
    }

    /// Translates `Infinity` or `NaN` after a sign: `-Infinity` keeps it, the others drop it.
    private void nonFinite(byte sign) {
        var infinity = source[p] == 'I';
        var word = infinity ? INFINITY : NAN;
        if (sign == '-' && infinity) {
            emit((byte) '-');
        }
        emit(word, 0, word.length);
        p += word.length;
    }

    private void hexNumber(int start, boolean negative) {
        var digits = p + 2;
        p = digits;
        while (p < end && Character.digit(source[p], HEX_RADIX) >= 0) {
            p++;
        }
        if (p == digits) {
            throw error(p, "expected a hexadecimal digit after '0x', found " + describe(p));
        }
        checkLength(start);
        var value = new BigInteger(new String(source, digits, p - digits, ISO_8859_1), HEX_RADIX);
        emitAscii((negative ? "-" : "") + value);
    }

    private void decimal(int start, byte sign) {
        var integer = p;
        integerPart();
        var integerEnd = p;
        var fraction = -1;
        if (p < end && source[p] == '.') {
            p++;
            fraction = p;
            skipDigits();
        }
        if (integerEnd == integer && (fraction < 0 || p == fraction)) {
            var after = fraction < 0 ? integer : fraction;
            throw error(after, "expected a digit after " + describe(after - 1) + ", found " + describe(after));
        }
        if (sign == '-') {
            emit((byte) '-');
        }
        digits(integer, integerEnd);
        if (fraction >= 0) {
            emit((byte) '.');
            digits(fraction, p);
        }
        exponent(start);
    }

    private void integerPart() {
        if (p < end && source[p] == '0') {
            p++;
            if (p < end && isDigit(source[p])) {
                throw error(p, "a number cannot have a leading zero");
            }
        } else {
            skipDigits();
        }
    }

    /// Writes the digits between two offsets, or 0 when there are none: `.5` and `5.` have none on one side.
    private void digits(int from, int to) {
        if (to == from) {
            emit((byte) '0');
        } else {
            emit(source, from, to - from);
        }
    }

    private void exponent(int start) {
        if (p >= end || (source[p] != 'e' && source[p] != 'E')) {
            return;
        }
        var from = p;
        p++;
        if (p < end && (source[p] == '+' || source[p] == '-')) {
            p++;
        }
        var digits = p;
        skipDigits();
        if (p == digits) {
            throw error(p, "expected a digit in the exponent, found " + describe(p));
        }
        emit(source, from, p - from);
        checkLength(start);
    }

    private void skipDigits() {
        while (p < end && isDigit(source[p])) {
            p++;
        }
    }

    private void checkLength(int start) {
        if (p - start > limits.maxNumberLength()) {
            throw error(start, "the number is longer than the maximum of " + limits.maxNumberLength() + " bytes");
        }
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private boolean startsWith(int at, byte[] word) {
        return at + word.length <= end && Arrays.equals(source, at, at + word.length, word, 0, word.length);
    }

    // Identifiers.

    /// Returns the offset after the character that starts an identifier at an offset, or -1 if none does: a letter,
    /// `$`, `_`, or a Unicode escape sequence of one.
    private int identifierStart(int at) {
        var b = source[at];
        if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || b == '$' || b == '_' || b == '\\') {
            return at + 1;
        }
        if (b >= 0) {
            return -1;
        }
        var after = Utf8.sequenceEnd(source, at, end);
        return after >= 0 && isIdentifierStart(Utf8.codePoint(source, at, after - at)) ? after : -1;
    }

    private static boolean isIdentifierStart(int codePoint) {
        if (codePoint == '$' || codePoint == '_') {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER,
                    Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER,
                    Character.LETTER_NUMBER -> true;
            default -> false;
        };
    }

    private static boolean isIdentifierPart(int codePoint) {
        if (isIdentifierStart(codePoint) || codePoint == ZERO_WIDTH_NON_JOINER || codePoint == ZERO_WIDTH_JOINER) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK,
                    Character.COMBINING_SPACING_MARK,
                    Character.DECIMAL_DIGIT_NUMBER,
                    Character.CONNECTOR_PUNCTUATION -> true;
            default -> false;
        };
    }

    /// Translates an identifier: quoted as a name, or as it is as a value, for [#literal(int)] to check.
    private void identifier(boolean name) {
        if (name) {
            emit((byte) '"');
        }
        var first = true;
        while (p < end) {
            var b = source[p];
            int codePoint;
            int after;
            if (b == '\\') {
                if (p + 1 >= end || source[p + 1] != 'u') {
                    throw error(p + 1, "expected 'u' after '\\' in an identifier, found " + describe(p + 1));
                }
                codePoint = hex(p + 2, UNICODE_HEX_DIGITS, "\\u");
                after = p + UNICODE_ESCAPE_LENGTH;
            } else if (b >= 0) {
                codePoint = b;
                after = p + 1;
            } else {
                codePoint = codePoint(p);
                after = Utf8.sequenceEnd(source, p, end);
            }
            if (first ? !isIdentifierStart(codePoint) : !isIdentifierPart(codePoint)) {
                if (b == '\\') {
                    throw error(p, "\\u%04X is not a character of an identifier".formatted(codePoint));
                }
                break;
            }
            shift(size, p);
            if (b == '\\') {
                // As an escape sequence, so that a name with a quote or a backslash in it stays one string.
                unicodeEscape(codePoint);
            } else {
                emit(source, p, after - p);
            }
            p = after;
            first = false;
        }
        if (name) {
            emit((byte) '"');
        }
    }

    /// Checks that the identifier that ends at [#p] is a literal, which a value can be.
    private void literal(int start) {
        var word = new String(source, start, p - start, ISO_8859_1);
        switch (word) {
            case "true", "false", "null", "Infinity", "NaN" -> {
                // Written as they are: the tape builder reads them.
            }
            default ->
                throw error(
                        start,
                        "expected a value, found "
                                + TapeBuilder.describeWord(source, start, end, "the end of the document"));
        }
    }

    // Output.

    private void emit(byte b) {
        if (size == json.length) {
            json = Arrays.copyOf(json, json.length * 2);
        }
        json[size++] = b;
    }

    private void emit(byte[] bytes, int from, int length) {
        if (size + length > json.length) {
            json = Arrays.copyOf(json, Math.max(json.length * 2, size + length));
        }
        System.arraycopy(bytes, from, json, size, length);
        size += length;
    }

    private void emitAscii(String text) {
        for (var i = 0; i < text.length(); i++) {
            emit((byte) text.charAt(i));
        }
    }

    /// Records that the next byte written comes from [#p].
    private void shift() {
        shift(size, p);
    }

    /// Records that the byte written at an offset comes from an offset of the source, unless the offsets already
    /// move together there.
    private void shift(int at, int from) {
        if (shifts > 0 && original[shifts - 1] - translated[shifts - 1] == from - at) {
            return;
        }
        if (shifts == translated.length) {
            translated = Arrays.copyOf(translated, shifts * 2);
            original = Arrays.copyOf(original, shifts * 2);
        }
        translated[shifts] = at;
        original[shifts] = from;
        shifts++;
    }

    // Errors.

    /// Returns the code point of the UTF-8 sequence at an offset, throwing if it is not valid.
    private int codePoint(int at) {
        var after = Utf8.sequenceEnd(source, at, end);
        if (after < 0) {
            var length = Math.max(1, Math.min(Utf8.sequenceLength(Byte.toUnsignedInt(source[at])), end - at));
            throw error(at, TapeBuilder.invalidUtf8(source, at, length));
        }
        return Utf8.codePoint(source, at, after - at);
    }

    private String describe(int at) {
        return TapeBuilder.describe(source, at, end, "the end of the document");
    }

    /// Returns an error at an offset of the source, unless the tape builder finds one earlier, in what was written
    /// before the token it is in.
    private TapeException error(int offset, String description) {
        var error = new TapeException(description, offset, "", Locator.of(source));
        return Translations.error(json, tokenStart, limits, Syntax.JSON5, locator(), error);
    }
}
