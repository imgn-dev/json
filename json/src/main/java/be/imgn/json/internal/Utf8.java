package be.imgn.json.internal;

import java.util.List;

/// The structure of UTF-8 sequences, as [RFC 3629](https://www.rfc-editor.org/rfc/rfc3629) restricts them: no overlong
/// forms, no surrogates, nothing above U+10FFFF.
final class Utf8 {

    /// The length of the longest sequence, the only one that encodes a surrogate pair in UTF-16.
    static final int MAX_LENGTH = 4;

    // For the checks of whole sequences in a little-endian int: the lead byte in the low byte, then the others.
    /// The lead byte of a little-endian int.
    static final int LEAD_MASK = 0xFF;
    /// The bits that tell a lead byte of a 2-byte sequence, and their value.
    static final int TWO_MASK = 0xE0;
    /// The value of [#TWO_MASK] bits of a lead byte of a 2-byte sequence.
    static final int TWO_TAG = 0xC0;
    /// The smallest lead byte of a 2-byte sequence: C0 and C1 lead overlong forms.
    static final int TWO_MIN = 0xC2;
    /// The bits that tell a lead byte of a 3-byte sequence.
    static final int THREE_MASK = 0xF0;
    /// The value of [#THREE_MASK] bits of a lead byte of a 3-byte sequence.
    static final int THREE_TAG = 0xE0;
    /// The lead byte whose second byte must be at least A0, against overlong forms.
    static final int THREE_OVERLONG = 0xE0;
    /// The lead byte whose second byte must be at most 9F, against surrogates.
    static final int THREE_SURROGATE = 0xED;
    /// The length of a 3-byte sequence.
    static final int THREE = 3;
    /// The bits that tell the second byte of a little-endian int is a continuation byte, and their value.
    static final int ONE_CONTINUATION_MASK = 0xC000;
    /// The value of [#ONE_CONTINUATION_MASK] bits when the second byte is a continuation byte.
    static final int ONE_CONTINUATION = 0x8000;
    /// The bits that tell the second and third bytes of a little-endian int are continuation bytes.
    static final int TWO_CONTINUATIONS_MASK = 0xC0C000;
    /// The value of [#TWO_CONTINUATIONS_MASK] bits when they are.
    static final int TWO_CONTINUATIONS = 0x808000;

    private static final int CONTINUATION_MASK = 0xC0;
    private static final int CONTINUATION_TAG = 0x80;
    private static final int CONTINUATION_PAYLOAD = 0x3F;
    private static final int CONTINUATION_BITS = 6;
    // The lead byte of a 4-byte sequence, and the first code points that need 2, 3 and 4 bytes.
    private static final int FOUR_TAG = 0xF0;
    private static final int TWO_BYTES_FROM = 0x80;
    private static final int THREE_BYTES_FROM = 0x800;
    private static final int FOUR_BYTES_FROM = 0x10000;
    // The bits of the lead byte that belong to the code point, by length of the sequence.
    private static final int[] LEAD_PAYLOAD = {0, 0x7F, 0x1F, 0x0F, 0x07};

    // The ranges of lead bytes of valid sequences.
    private static final List<Range> RANGES = List.of(
            new Range(0xC2, 0xDF, 2, 0x80, 0xBF),
            new Range(0xE0, 0xE0, 3, 0xA0, 0xBF),
            new Range(0xE1, 0xEC, 3, 0x80, 0xBF),
            new Range(0xED, 0xED, 3, 0x80, 0x9F),
            new Range(0xEE, 0xEF, 3, 0x80, 0xBF),
            new Range(0xF0, 0xF0, 4, 0x90, 0xBF),
            new Range(0xF1, 0xF3, 4, 0x80, 0xBF),
            new Range(0xF4, 0xF4, 4, 0x80, 0x8F));

    // By lead byte: the length of its sequences in the low byte, and the range of the byte after it in the next two
    // bytes; 0 for a byte that leads no valid sequence. One lookup tells all a lead byte allows.
    private static final int[] INFO = new int[1 << Byte.SIZE];
    private static final int BYTE_MASK = 0xFF;
    private static final int MIN_SHIFT = Byte.SIZE;
    private static final int MAX_SHIFT = 2 * Byte.SIZE;

    static {
        for (var range : RANGES) {
            for (var lead = range.firstLead(); lead <= range.lastLead(); lead++) {
                INFO[lead] = range.length() | (range.secondMin() << MIN_SHIFT) | (range.secondMax() << MAX_SHIFT);
            }
        }
    }

    private Utf8() {}

    /// Returns the length of the sequence a non-ASCII byte leads, or 0 if no valid sequence starts with it.
    static int sequenceLength(int lead) {
        return length(INFO[lead]);
    }

    /// Returns what a lead byte allows, for [#length(int)] and [#isValidSecond(int, int)].
    static int info(int lead) {
        return INFO[lead];
    }

    /// Returns the length of the sequences of a lead byte, from its [#info(int)].
    static int length(int info) {
        return info & BYTE_MASK;
    }

    /// Returns whether a byte may follow a lead byte, given by its [#info(int)].
    static boolean isValidSecond(int info, int second) {
        return second >= ((info >>> MIN_SHIFT) & BYTE_MASK) && second <= (info >>> MAX_SHIFT);
    }

    /// Returns whether a byte is a continuation byte.
    static boolean isContinuation(int b) {
        return (b & CONTINUATION_MASK) == CONTINUATION_TAG;
    }

    /// Returns the offset after the UTF-8 sequence that starts with the non-ASCII byte at an offset, or -1 if it is not
    /// a valid sequence that ends before `end`.
    static int sequenceEnd(byte[] input, int p, int end) {
        var info = info(Byte.toUnsignedInt(input[p]));
        var length = length(info);
        if (length == 0 || p + length > end || !isValidSecond(info, Byte.toUnsignedInt(input[p + 1]))) {
            return -1;
        }
        for (var i = p + 2; i < p + length; i++) {
            if (!isContinuation(input[i])) {
                return -1;
            }
        }
        return p + length;
    }

    /// Returns the code point of a valid sequence.
    static int codePoint(byte[] input, int from, int length) {
        var codePoint = input[from] & LEAD_PAYLOAD[length];
        for (var i = from + 1; i < from + length; i++) {
            codePoint = (codePoint << CONTINUATION_BITS) | (input[i] & CONTINUATION_PAYLOAD);
        }
        return codePoint;
    }

    /// Writes the UTF-8 sequence of a code point at an offset, and returns the offset after it.
    static int encode(int codePoint, byte[] target, int at) {
        if (codePoint < TWO_BYTES_FROM) {
            target[at] = (byte) codePoint;
            return at + 1;
        }
        if (codePoint < THREE_BYTES_FROM) {
            target[at] = (byte) (TWO_TAG | codePoint >>> CONTINUATION_BITS);
            target[at + 1] = continuation(codePoint);
            return at + 2;
        }
        if (codePoint < FOUR_BYTES_FROM) {
            target[at] = (byte) (THREE_TAG | codePoint >>> (2 * CONTINUATION_BITS));
            target[at + 1] = continuation(codePoint >>> CONTINUATION_BITS);
            target[at + 2] = continuation(codePoint);
            return at + THREE;
        }
        target[at] = (byte) (FOUR_TAG | codePoint >>> (THREE * CONTINUATION_BITS));
        target[at + 1] = continuation(codePoint >>> (2 * CONTINUATION_BITS));
        target[at + 2] = continuation(codePoint >>> CONTINUATION_BITS);
        target[at + THREE] = continuation(codePoint);
        return at + MAX_LENGTH;
    }

    /// Returns the continuation byte of the low 6 bits of a code point.
    private static byte continuation(int bits) {
        return (byte) (CONTINUATION_TAG | (bits & CONTINUATION_PAYLOAD));
    }

    /// The lead bytes of a valid sequence, by range: the length of their sequences, and the range of the byte after
    /// them, which is narrower than a continuation byte's where the shortest form or the surrogates require it.
    private record Range(int firstLead, int lastLead, int length, int secondMin, int secondMax) {}
}
