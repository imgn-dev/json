package be.imgn.json.tests;

import be.imgn.json.bind.JsonName;
import java.net.URI;
import java.util.UUID;

/// A customer.
///
/// @param name the name
/// @param email the e-mail address
/// @param id the identifier
/// @param site the web site
public record Customer(String name, @JsonName("e-mail") String email, UUID id, URI site) {}
