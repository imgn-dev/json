package be.imgn.json.tests;

import be.imgn.json.bind.JsonName;


/// A rectangle, with a tag of its own.
///
/// @param width the width
/// @param height the height
@JsonName("rectangle")
public record Rectangle(double width, double height) implements Polygon {}
