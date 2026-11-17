package be.imgn.json.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// Splits [JSON Lines](https://jsonlines.org) into lines, and builds the tape of each, one at a time.
///
/// Lines end with `\n`; the last one may not. Each line is a document of its own, built from its range of the input:
/// the `\r` of a `\r\n` is whitespace at its end, and a blank line is an error, as it holds no value.
///
/// A stream is read in chunks, never reused, since a document keeps its input; a line split between two chunks is
/// copied into the next one. A line longer than the maximum length of a document is rejected before it is read whole.
public final class Lines implements Iterator<Tape> {

    private static final int CHUNK = 1 << 16;
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    private final @Nullable InputStream in;
    private final Limits limits;
    private byte[] buffer;
    // The bytes of the buffer read so far, and where the next line starts.
    private int filled;
    private int start;
    private int line = 1;
    private boolean ended;
    private @Nullable Tape next;

    private Lines(@Nullable InputStream in, byte[] buffer, int filled, Limits limits) {
        this.in = in;
        this.buffer = buffer;
        this.filled = filled;
        this.ended = in == null;
        this.limits = limits;
    }

    /// Returns the lines of an input.
    ///
    /// @param input the input, in UTF-8; it is not copied, and must not change while the documents are used
    public static Lines of(byte[] input, Limits limits) {
        return new Lines(null, input, input.length, limits);
    }

    /// Returns the lines of a stream, read as they are asked for; the stream is not closed.
    public static Lines of(InputStream in, Limits limits) {
        return new Lines(in, new byte[CHUNK], 0, limits);
    }

    /// Returns whether a line follows.
    ///
    /// @throws TapeException if the next line is not a JSON value, or exceeds a limit
    /// @throws UncheckedIOException if the stream cannot be read
    @Override
    public boolean hasNext() {
        if (next == null) {
            next = read();
        }
        return next != null;
    }

    /// Returns the tape of the next line.
    ///
    /// @throws TapeException if the next line is not a JSON value, or exceeds a limit
    /// @throws UncheckedIOException if the stream cannot be read
    @Override
    public Tape next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        var tape = Objects.requireNonNull(next);
        next = null;
        return tape;
    }

    private @Nullable Tape read() {
        while (true) {
            var newline = indexOfNewline();
            if (newline >= 0) {
                return build(newline, newline + 1);
            }
            if (ended) {
                return start < filled ? build(filled, filled) : null;
            }
            fill();
        }
    }

    private int indexOfNewline() {
        for (var p = start; p < filled; p++) {
            if (buffer[p] == '\n') {
                return p;
            }
        }
        return -1;
    }

    private Tape build(int end, int after) {
        var from = start;
        var number = line;
        start = after;
        line++;
        return TapeBuilder.build(buffer, from, end, limits, Syntax.JSON_LINE, Locator.ofLine(buffer, from, number));
    }

    /// Reads more of the stream, into a new buffer if this one is full.
    private void fill() {
        var partial = filled - start;
        if (partial > limits.maxDocumentLength()) {
            // The tape builder reports the line as too long, from its length alone.
            build(filled, filled);
        }
        if (filled == buffer.length) {
            var larger = new byte[(int) Math.min(MAX_ARRAY, Math.max(CHUNK, 2L * partial))];
            System.arraycopy(buffer, start, larger, 0, partial);
            buffer = larger;
            filled = partial;
            start = 0;
        }
        try {
            var read = requireStream().read(buffer, filled, buffer.length - filled);
            if (read < 0) {
                ended = true;
            } else {
                filled += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InputStream requireStream() {
        if (in == null) {
            throw new IllegalStateException("no stream to read");
        }
        return in;
    }
}
