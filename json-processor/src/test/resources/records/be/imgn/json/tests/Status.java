package be.imgn.json.tests;

import be.imgn.json.JsonName;

/// The status of an order.
public enum Status {
    /// New.
    NEW,
    /// In progress.
    @JsonName("in-progress")
    IN_PROGRESS,
    /// Done.
    DONE
}
