package be.imgn.json.tests;

import be.imgn.json.bind.JsonRecord;
import be.imgn.json.bind.JsonName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// An order, with components of most types.
///
/// @param id the identifier
/// @param customer the customer
/// @param lines the lines
/// @param status the status
/// @param shipped when the order was shipped, if it was
/// @param notes notes by name
/// @param tags the tags
/// @param total the total
@JsonRecord
public record Order(
        long id,
        Customer customer,
        List<Line> lines,
        Status status,
        @Nullable Instant shipped,
        Map<String, String> notes,
        Set<String> tags,
        @JsonName("total-price") BigDecimal total) {}
