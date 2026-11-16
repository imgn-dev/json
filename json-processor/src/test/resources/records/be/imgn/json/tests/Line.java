package be.imgn.json.tests;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/// A line of an order, with components of the other types.
///
/// @param product the product
/// @param quantity the quantity
/// @param price the price
/// @param discount the discount, if any
/// @param at when it was added
/// @param day its day
/// @param time its time
/// @param local its local date-time
/// @param delay the delay of delivery
/// @param serial the serial number
/// @param shelf the shelf
/// @param bin the bin
/// @param weight the weight
/// @param gift whether it is a gift
public record Line(
        String product,
        int quantity,
        double price,
        @Nullable Integer discount,
        OffsetDateTime at,
        LocalDate day,
        LocalTime time,
        LocalDateTime local,
        Duration delay,
        BigInteger serial,
        short shelf,
        byte bin,
        float weight,
        boolean gift) {}
