package be.imgn.json.tests;

import be.imgn.json.bind.JsonRecord;
import java.util.List;

/// A tree: a record that refers to itself.
///
/// @param name the name
/// @param children the children
@JsonRecord
public record Node(String name, List<Node> children) {}
